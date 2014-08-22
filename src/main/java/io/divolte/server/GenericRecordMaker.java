package io.divolte.server;

import io.divolte.server.geo2ip.LookupService;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import net.sf.uadetector.OperatingSystem;
import net.sf.uadetector.ReadableUserAgent;
import net.sf.uadetector.UserAgentStringParser;
import net.sf.uadetector.service.UADetectorServiceFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Continent;
import com.maxmind.geoip2.record.Country;
import com.maxmind.geoip2.record.Location;
import com.maxmind.geoip2.record.Postal;
import com.maxmind.geoip2.record.Subdivision;
import com.maxmind.geoip2.record.Traits;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

import static io.divolte.server.DivolteEventHandler.*;

/*
 * This class is called maker, because builder was already taken by Avro itself.
 */
@ParametersAreNonnullByDefault
@NotThreadSafe
final class GenericRecordMaker {
    private final static Logger logger = LoggerFactory.getLogger(GenericRecordMaker.class);

    private final String sessionIdCookie;

    private final Schema schema;
    private final Map<String, Pattern> regexes;
    private final List<FieldSetter> setters;

    private final LoadingCache<String,ReadableUserAgent> uaLookupCache;
    private final Optional<LookupService> geoipService;

    public GenericRecordMaker(final Schema schema,
                              final Config schemaConfig,
                              final Config globalConfig,
                              final Optional<LookupService> geoipService) {
        Objects.requireNonNull(schemaConfig);
        Objects.requireNonNull(globalConfig);

        this.sessionIdCookie = globalConfig.getString("divolte.tracking.session_cookie");

        final int version = schemaConfig.getInt("divolte.tracking.schema_mapping.version");
        checkVersion(version);

        this.regexes = regexMapFromConfig(schemaConfig);
        this.setters = setterListFromConfig(schema, schemaConfig);

        this.schema = Objects.requireNonNull(schema);

        final UserAgentStringParser parser = parserBasedOnTypeConfig(globalConfig.getString("divolte.tracking.ua_parser.type"));
        this.uaLookupCache = sizeBoundCacheFromLoadingFunction(parser::parse, globalConfig.getInt("divolte.tracking.ua_parser.cache_size"));

        this.geoipService = Objects.requireNonNull(geoipService);

        logger.info("User agent parser data version: {}", parser.getDataVersion());
    }

    private static UserAgentStringParser parserBasedOnTypeConfig(String type) {
        switch (type) {
        case "caching_and_updating":
            logger.info("Using caching and updating user agent parser.");
            return UADetectorServiceFactory.getCachingAndUpdatingParser();
        case "online_updating":
            logger.info("Using online updating user agent parser.");
            return UADetectorServiceFactory.getOnlineUpdatingParser();
        case "non_updating":
            logger.info("Using non-updating (resource module based) user agent parser.");
            return UADetectorServiceFactory.getResourceModuleParser();
        default:
            throw new RuntimeException("Invalid user agent parser type. Valid values are: caching_and_updating, online_updating, non_updating.");
        }
    }

    private static FieldSetter fieldSetterFromConfig(final Schema schema, final Entry<String, ConfigValue> entry) {
        final String fieldName = entry.getKey();
        final Schema.Field field = schema.getField(fieldName);
        if (null == field) {
            throw new SchemaMappingException("Schema missing mapped field: %s", fieldName);
        }
        final FieldProducer<?> fieldProducer = fieldGetterFromConfig(entry);
        return (b, e, c) -> fieldProducer.get(c).ifPresent((v) -> b.set(field, v));
    }

    private static FieldProducer<?> fieldGetterFromConfig(final Entry<String, ConfigValue> entry) {
        final ConfigValue value = entry.getValue();

        switch (value.valueType()) {
        case STRING:
            return simpleFieldGetter((String) value.unwrapped());
        case OBJECT:
            final String fieldName = entry.getKey();
            final Config subConfig = ((ConfigObject) value).toConfig();
            if (!subConfig.hasPath("type")) {
                throw new SchemaMappingException("Missing type property on configuration for field %s.", fieldName);
            }
            final String type = subConfig.getString("type");
            return complexFieldGetterForConfig(fieldName, type, subConfig);
        default:
            throw new SchemaMappingException("Schema mapping for fields can only be of type STRING or OBJECT. Found %s.", value.valueType());
        }
    }

    private static FieldProducer<?> complexFieldGetterForConfig(final String name, final String type, final Config config) {
        switch (type) {
        case "cookie":
            return (c) -> Optional.ofNullable(c.getServerExchange().getRequestCookies().get(config.getString("name")))
                                  .map(Cookie::getValue);
        case "regex_group":
            return regexGroupFieldGetter(config);
        case "regex_name":
            return regexNameFieldGetter(config);
        default:
            throw new SchemaMappingException("Unknown mapping type: %s for field %s.", type, name);
        }
    }

    private static FieldProducer<String> regexNameFieldGetter(final Config config) {
        final Stream<String> regexNames = config.getStringList("regexes").stream();
        final String fieldName = config.getString("field");
        final FieldProducer<String> fieldProducer = regexFieldGetterForName(fieldName);

        return (c) -> fieldProducer.get(c)
                                 .flatMap((s) -> regexNames.filter((rn) -> c.matcher(rn, fieldName, s).matches())
                                                           .findFirst());
    }

    private static FieldProducer<String> regexGroupFieldGetter(final Config config) {
        final String regexName = config.getString("regex");
        final String fieldName = config.getString("field");
        final String groupName = config.getString("group");
        final FieldProducer<String> fieldProducer = regexFieldGetterForName(fieldName);

        return (c) -> fieldProducer.get(c)
                                 .flatMap((s) -> groupFromMatcher(c.matcher(regexName, fieldName, s), groupName));
    }

    private static final FieldProducer<String> REMOTE_HOST_FIELD_PRODUCER =
            (c) -> Optional.ofNullable(c.getServerExchange().getSourceAddress())
                           .flatMap((a) -> Optional.ofNullable(a.getHostString()));
    private static final FieldProducer<String> REFERER_FIELD_PRODUCER = (c) -> c.getQueryParameter("r");
    private static final FieldProducer<String> LOCATION_FIELD_PRODUCER = (c) -> c.getQueryParameter("l");
    private static final FieldProducer<String> USERAGENT_FIELD_PRODUCER = Context::getUserAgent;
    private static final FieldProducer<Long> TIMESTAMP_FIELD_PRODUCER = (c) -> c.getAttachment(REQUEST_START_TIME_KEY);
    private static final FieldProducer<String> PAGE_VIEW_ID_PRODUCER = (c) -> c.getAttachment(PAGE_VIEW_ID_KEY);

    private static FieldProducer<String> regexFieldGetterForName(final String name) {
        switch (name) {
        case "userAgent":
            return USERAGENT_FIELD_PRODUCER;
        case "remoteHost":
            return REMOTE_HOST_FIELD_PRODUCER;
        case "referer":
            return REFERER_FIELD_PRODUCER;
        case "location":
            return LOCATION_FIELD_PRODUCER;
        default:
            throw new SchemaMappingException("Only userAgent, remoteHost, referer and location fields can be used for regex matchers. Found %s.", name);
        }
    }

    private static FieldProducer<?> simpleFieldGetter(final String name) {
        switch (name) {
        case "firstInSession":
            return (c) -> Optional.of(c.isFirstInSession());
        case "geoCityId":
            return (c) -> c.getCity().map(City::getGeoNameId);
        case "geoCityName":
            return (c) -> c.getCity().map(City::getName);
        case "geoContinentCode":
            return (c) -> c.getContinent().map(Continent::getCode);
        case "geoContinentId":
            return (c) -> c.getContinent().map(Continent::getGeoNameId);
        case "geoContinentName":
            return (c) -> c.getContinent().map(Continent::getName);
        case "geoCountryCode":
            return (c) -> c.getCountry().map(Country::getIsoCode);
        case "geoCountryId":
            return (c) -> c.getCountry().map(Country::getGeoNameId);
        case "geoCountryName":
            return (c) -> c.getCountry().map(Country::getName);
        case "geoLatitude":
            return (c) -> c.getLocation().map(Location::getLatitude);
        case "geoLongitude":
            return (c) -> c.getLocation().map(Location::getLongitude);
        case "geoMetroCode":
            return (c) -> c.getLocation().map(Location::getMetroCode);
        case "geoTimeZone":
            return (c) -> c.getLocation().map(Location::getTimeZone);
        case "geoMostSpecificSubdivisionCode":
            return (c) -> c.getMostSpecificSubdivision().map(Subdivision::getIsoCode);
        case "geoMostSpecificSubdivisionId":
            return (c) -> c.getMostSpecificSubdivision().map(Subdivision::getGeoNameId);
        case "geoMostSpecificSubdivisionName":
            return (c) -> c.getMostSpecificSubdivision().map(Subdivision::getName);
        case "geoPostalCode":
            return (c) -> c.getGeoField((r) -> r.getPostal()).map(Postal::getCode);
        case "geoRegisteredCountryCode":
            return (c) -> c.getRegisteredCountry().map(Country::getIsoCode);
        case "geoRegisteredCountryId":
            return (c) -> c.getRegisteredCountry().map(Country::getGeoNameId);
        case "geoRegisteredCountryName":
            return (c) -> c.getRegisteredCountry().map(Country::getName);
        case "geoRepresentedCountryCode":
            return (c) -> c.getRepresentedCountry().map(Country::getIsoCode);
        case "geoRepresentedCountryId":
            return (c) -> c.getRepresentedCountry().map(Country::getGeoNameId);
        case "geoRepresentedCountryName":
            return (c) -> c.getRepresentedCountry().map(Country::getName);
        case "geoSubdivisionCodes":
            return (c) -> c.getSubdivisions().map((s) -> Lists.transform(s, Subdivision::getIsoCode));
        case "geoSubdivisionIds":
            return (c) -> c.getSubdivisions().map((s) -> Lists.transform(s, Subdivision::getGeoNameId));
        case "geoSubdivisionNames":
            return (c) -> c.getSubdivisions().map((s) -> Lists.transform(s, Subdivision::getName));
        case "geoAutonomousSystemNumber":
            return (c) -> c.getTraits().map(Traits::getAutonomousSystemNumber);
        case "geoAutonomousSystemOrganization":
            return (c) -> c.getTraits().map(Traits::getAutonomousSystemOrganization);
        case "geoDomain":
            return (c) -> c.getTraits().map(Traits::getDomain);
        case "geoIsp":
            return (c) -> c.getTraits().map(Traits::getIsp);
        case "geoOrganisation":
            return (c) -> c.getTraits().map(Traits::getOrganization);
        case "geoAnonymousProxy":
            return (c) -> c.getTraits().map(Traits::isAnonymousProxy);
        case "geoSatelliteProvider":
            return (c) -> c.getTraits().map(Traits::isSatelliteProvider);
        case "timestamp":
            return TIMESTAMP_FIELD_PRODUCER;
        case "userAgent":
            return USERAGENT_FIELD_PRODUCER;
        case "userAgentName":
            return (c) -> c.getUserAgentLookup().map(ReadableUserAgent::getName);
        case "userAgentFamily":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getFamily().getName());
        case "userAgentVendor":
            return (c) -> c.getUserAgentLookup().map(ReadableUserAgent::getProducer);
        case "userAgentType":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getType().getName());
        case "userAgentVersion":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getVersionNumber().toVersionString());
        case "userAgentDeviceCategory":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getDeviceCategory().getName());
        case "userAgentOsFamily":
            return (c) -> c.getUserAgentOperatingSystem().map((os) -> os.getFamily().getName());
        case "userAgentOsVersion":
            return (c) -> c.getUserAgentOperatingSystem().map((os) -> os.getVersionNumber().toVersionString());
        case "userAgentOsVendor":
            return (c) -> c.getUserAgentOperatingSystem().map(OperatingSystem::getProducer);
        case "remoteHost":
            return REMOTE_HOST_FIELD_PRODUCER;
        case "referer":
            return REFERER_FIELD_PRODUCER;
        case "location":
            return LOCATION_FIELD_PRODUCER;
        case "viewportPixelWidth":
            return (c) -> c.getQueryParameter("w").map(Ints::tryParse);
        case "viewportPixelHeight":
            return (c) -> c.getQueryParameter("h").map(Ints::tryParse);
        case "screenPixelWidth":
            return (c) -> c.getQueryParameter("i").map(Ints::tryParse);
        case "screenPixelHeight":
            return (c) -> c.getQueryParameter("j").map(Ints::tryParse);
        case "partyId":
            return (c) -> c.getAttachment(PARTY_COOKIE_KEY).map(CookieValues.CookieValue::getValue);
        case "sessionId":
            return (c) -> c.getAttachment(SESSION_COOKIE_KEY).map(CookieValues.CookieValue::getValue);
        case "pageViewId":
            return PAGE_VIEW_ID_PRODUCER;
        default:
            throw new SchemaMappingException("Unknown field in schema mapping: %s", name);
        }
    }

    private static List<FieldSetter> setterListFromConfig(final Schema schema, final Config config) {
        if (!config.hasPath("divolte.tracking.schema_mapping.fields")) {
            throw new SchemaMappingException("Schema mapping configuration has no field mappings.");
        }

        final Set<Entry<String, ConfigValue>> entrySet = config.getConfig("divolte.tracking.schema_mapping.fields").root().entrySet();

        return entrySet.stream()
            .map((e) -> fieldSetterFromConfig(schema, e))
            .collect(Collectors.toCollection(() -> new ArrayList<>(entrySet.size())));
    }

    private static Map<String,Pattern> regexMapFromConfig(final Config config) {
        return config.hasPath("divolte.tracking.schema_mapping.regexes") ?
        config.getConfig("divolte.tracking.schema_mapping.regexes").root().entrySet().stream().collect(
                Collectors.<Entry<String,ConfigValue>, String, Pattern>toMap(
                Entry::getKey,
                (e) -> {
                    if (e.getValue().valueType() != ConfigValueType.STRING) {
                        throw new SchemaMappingException("Regexes config elements must be of type STRING. Found %s of type %s.", e.getKey(), e.getValue().valueType());
                    }
                    return Pattern.compile((String) e.getValue().unwrapped());
                })) : Collections.emptyMap();
    }

    private static void checkVersion(final int version) {
        if (version != 1) {
            throw new SchemaMappingException("Unsupported schema mapping configuration version: %d", version);
        }
    }

    private static Optional<String> groupFromMatcher(final Matcher matcher, final String group) {
        return matcher.matches() ? Optional.ofNullable(matcher.group(group)) : Optional.empty();
    }

    @FunctionalInterface
    private interface FieldSetter {
        void setFields(GenericRecordBuilder builder, HttpServerExchange exchange, Context context);
    }

    @FunctionalInterface
    private interface FieldProducer<T> {
        Optional<T> get(Context context);
    }

    public GenericRecord makeRecordFromExchange(final HttpServerExchange exchange) {
        final GenericRecordBuilder builder = new GenericRecordBuilder(schema);
        final Context context = new Context(exchange);
        setters.forEach((s) -> s.setFields(builder, exchange, context));
        return builder.build();
    }

    @ParametersAreNonnullByDefault
    public static class SchemaMappingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SchemaMappingException(String message) {
            super(message);
        }

        public SchemaMappingException(String message, Object... args) {
            this(String.format(message, args));
        }
    }

    private static <K,V> LoadingCache<K, V> sizeBoundCacheFromLoadingFunction(Function<K, V> loader, int size) {
        return CacheBuilder
                .newBuilder()
                .maximumSize(size)
                .initialCapacity(size)
                .build(new CacheLoader<K, V>() {
                    @Override
                    public V load(K key) throws Exception {
                        return loader.apply(key);
                    }
                });
    }

    private Optional<ReadableUserAgent> parseUserAgent(final String userAgentString) {
        try {
            return Optional.of(uaLookupCache.get(userAgentString));
        } catch (final ExecutionException e) {
            logger.warn("Failed to parse user agent string for: " + userAgentString, e);
            return Optional.empty();
        }
    }

    @ParametersAreNonnullByDefault
    private final class Context {

        // In general a regular expression is used against a single value, but it can be used more than once.
        private final Map<String, Matcher> matchers = Maps.newHashMapWithExpectedSize(regexes.size() * 2);

        private final HttpServerExchange serverExchange;

        private final LazyReference<Optional<String>> userAgent;
        private final LazyReference<Optional<ReadableUserAgent>> userAgentLookup;
        private final LazyReference<Optional<CityResponse>> geoLookup;

        private Context(final HttpServerExchange serverExchange) {
            this.serverExchange = Objects.requireNonNull(serverExchange);
            this.userAgent = new LazyReference<>(() ->
                Optional.ofNullable(serverExchange.getRequestHeaders().getFirst(Headers.USER_AGENT)));
            this.userAgentLookup = new LazyReference<>(() ->
                getUserAgent().flatMap(GenericRecordMaker.this::parseUserAgent));
            this.geoLookup = new LazyReference<>(() -> geoipService.flatMap((service) -> {
                final InetSocketAddress sourceAddress = serverExchange.getSourceAddress();
                final InetAddress ipAddress = null != sourceAddress ? sourceAddress.getAddress() : null;
                return null != ipAddress ? service.lookup(ipAddress) : Optional.empty();
            }));
        }

        public Matcher matcher(final String regex, final String field, final String value) {
            final String key = regex + field;
            return matchers.computeIfAbsent(key, (ignored) -> regexes.get(regex).matcher(value));
        }

        public HttpServerExchange getServerExchange() {
            return serverExchange;
        }

        public Optional<String> getQueryParameter(final String parameterName) {
            return Optional.ofNullable(serverExchange.getQueryParameters().get(parameterName)).map(Deque::getFirst);
        }

        public <T> Optional<T> getAttachment(final AttachmentKey<T> key) {
            return Optional.of(serverExchange.getAttachment(key));
        }

        public boolean isFirstInSession() {
            return !serverExchange.getRequestCookies().containsKey(sessionIdCookie);
        }

        public Optional<String> getUserAgent() {
            return userAgent.get();
        }

        public Optional<ReadableUserAgent> getUserAgentLookup() {
            return userAgentLookup.get();
        }

        public Optional<OperatingSystem> getUserAgentOperatingSystem() {
            return getUserAgentLookup().map(ReadableUserAgent::getOperatingSystem);
        }

        private <T> Optional<T> getGeoField(Function<CityResponse, T> getter) {
            return geoLookup.get().flatMap((g) -> Optional.ofNullable(getter.apply(g)));
        }

        public Optional<City> getCity() {
            return getGeoField((g) -> g.getCity());
        }

        public Optional<Continent> getContinent() {
            return getGeoField((g) -> g.getContinent());
        }

        public Optional<Country> getCountry() {
            return getGeoField((g) -> g.getCountry());
        }

        public Optional<Location> getLocation() {
            return getGeoField((g) -> g.getLocation());
        }

        public Optional<Subdivision> getMostSpecificSubdivision() {
            return getGeoField((g) -> g.getMostSpecificSubdivision());
        }

        public Optional<Country> getRegisteredCountry() {
            return getGeoField((g) -> g.getRegisteredCountry());
        }

        public Optional<Country> getRepresentedCountry() {
            return getGeoField((g) -> g.getRepresentedCountry());
        }

        public Optional<List<Subdivision>> getSubdivisions() {
            return getGeoField((g) -> g.getSubdivisions());
        }

        public Optional<Traits> getTraits() {
            return getGeoField((g) -> g.getTraits());
        }
    }
}
