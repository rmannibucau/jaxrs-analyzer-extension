package com.github.rmannibucau.jaxrsanalyzer.backend;

import static com.sebastian_daschner.jaxrs_analyzer.backend.ComparatorUtils.mapKeyComparator;
import static com.sebastian_daschner.jaxrs_analyzer.backend.ComparatorUtils.parameterComparator;
import static com.sebastian_daschner.jaxrs_analyzer.model.Types.BOOLEAN;
import static com.sebastian_daschner.jaxrs_analyzer.model.Types.DOUBLE_TYPES;
import static com.sebastian_daschner.jaxrs_analyzer.model.Types.INTEGER_TYPES;
import static com.sebastian_daschner.jaxrs_analyzer.model.Types.PRIMITIVE_BOOLEAN;
import static com.sebastian_daschner.jaxrs_analyzer.model.Types.STRING;
import static java.util.Collections.singletonMap;
import static java.util.Comparator.comparing;
import static java.util.Locale.ROOT;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonPatch;
import javax.json.JsonWriter;
import javax.json.stream.JsonGenerator;
import javax.ws.rs.core.Response;

import com.sebastian_daschner.jaxrs_analyzer.LogProvider;
import com.sebastian_daschner.jaxrs_analyzer.backend.Backend;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.MethodParameter;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.ParameterType;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.Project;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.ResourceMethod;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.Resources;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.TypeIdentifier;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.TypeRepresentation;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.TypeRepresentationVisitor;
import com.sebastian_daschner.jaxrs_analyzer.utils.Pair;
import com.sebastian_daschner.jaxrs_analyzer.utils.StringUtils;

// big bad fork to add some x-*
public class EnrichedSwaggerBackend implements Backend {

    private static final String SWAGGER_VERSION = "2.0";

    private final Lock lock = new ReentrantLock();

    private final SwaggerOptions options = new SwaggerOptions();

    private Resources resources;

    private JsonObjectBuilder builder;

    private SchemaBuilder schemaBuilder;

    private String projectName;

    private String projectVersion;

    private final Collection<String> sections = new HashSet<>();

    @Override
    public String getName() {
        return "Swagger"; // until https://github.com/sdaschner/jaxrs-analyzer-maven-plugin/issues/50 is fixed
    }

    @Override
    public void configure(final Map<String, String> config) {
        options.configure(config);
    }

    @Override
    public byte[] render(final Project project) {
        lock.lock();
        try {
            // initialize fields
            builder = Json.createObjectBuilder();
            resources = project.getResources();
            projectName = project.getName();
            projectVersion = project.getVersion();
            schemaBuilder = new SchemaBuilder(resources.getTypeRepresentations());

            final JsonObject output = modifyJson(renderInternal());

            return serialize(output);
        } finally {
            sections.clear();
            lock.unlock();
        }
    }

    private JsonObject modifyJson(final JsonObject json) {
        if (options.getJsonPatch() == null)
            return json;
        return options.getJsonPatch().apply(json);
    }

    private JsonObject renderInternal() {
        appendHeader();
        appendPaths();
        appendDefinitions();

        if (!sections.isEmpty()) {
            final JsonArrayBuilder xRestletSections = sections.stream()
                                .collect(Json::createArrayBuilder, JsonArrayBuilder::add, JsonArrayBuilder::addAll);
            builder.add("x-restlet",
                    Json.createObjectBuilder()
                        .add("sections", xRestletSections)
                            .build());
        }

        return builder.build();
    }

    private void appendHeader() {
        builder.add("swagger", SWAGGER_VERSION)
                .add("info", Json.createObjectBuilder().add("version", projectVersion).add("title", projectName))
                .add("host", options.getDomain() == null ? "" : options.getDomain())
                .add("basePath",
                        (options.getDomain() != null && !"".equals(options.getDomain().trim()) ? '/' : '/' + projectName + '/')
                                + resources.getBasePath())
                .add("schemes", options.getSchemes().stream().map(Enum::name).map(String::toLowerCase).sorted()
                        .collect(Json::createArrayBuilder, JsonArrayBuilder::add, JsonArrayBuilder::add).build());
        if (options.isRenderTags()) {
            final JsonArrayBuilder tags = Json.createArrayBuilder();
            resources.getResources().stream().map(this::extractTag).filter(Objects::nonNull).distinct().sorted()
                    .map(tag -> Json.createObjectBuilder().add("name", tag)).forEach(tags::add);
            builder.add("tags", tags);
        }
    }

    private String extractTag(final String s) {
        final int offset = options.getTagsPathOffset();
        final String[] parts = s.split("/");

        if (parts.length > offset && !parts[offset].contains("{")) {
            return parts[offset];
        }
        return null;
    }

    private void appendPaths() {
        final JsonObjectBuilder paths = Json.createObjectBuilder();
        resources.getResources().stream().sorted().forEach(s -> {
            final JsonObjectBuilder endpoint = buildPathDefinition(s);
            int slash = s.indexOf('/');
            if (slash < 0) {
                slash = s.length();
            }
            final String section = Character.toUpperCase(s.charAt(0)) + s.substring(1, slash).replaceFirst("type", " Type");
            endpoint.add("x-restlet", Json.createObjectBuilder().add("section", section));
            sections.add(section);
            paths.add('/' + s, endpoint);
        });
        builder.add("paths", paths);
    }

    private JsonObjectBuilder buildPathDefinition(final String s) {
        final JsonObjectBuilder methods = Json.createObjectBuilder();
        resources.getMethods(s).stream().sorted(comparing(ResourceMethod::getMethod))
                .forEach(m -> methods.add(m.getMethod().toString().toLowerCase(ROOT), buildForMethod(m, s)));
        return methods;
    }

    private JsonObjectBuilder buildForMethod(final ResourceMethod method, final String s) {
        final JsonArrayBuilder consumes = Json.createArrayBuilder();
        method.getRequestMediaTypes().stream().sorted().forEach(consumes::add);

        final JsonArrayBuilder produces = Json.createArrayBuilder();
        method.getResponseMediaTypes().stream().sorted().forEach(produces::add);

        final JsonObjectBuilder builder = Json.createObjectBuilder();

        if (method.getDescription() != null)
            builder.add("description", method.getDescription());

        builder.add("consumes", consumes).add("produces", produces).add("parameters", buildParameters(method)).add("responses",
                buildResponses(method));

        if (method.isDeprecated())
            builder.add("deprecated", true);

        if (options.isRenderTags())
            Optional.ofNullable(extractTag(s)).ifPresent(t -> builder.add("tags", Json.createArrayBuilder().add(t)));

        return builder;
    }

    private JsonArrayBuilder buildParameters(final ResourceMethod method) {
        final Set<MethodParameter> parameters = method.getMethodParameters();
        final JsonArrayBuilder parameterBuilder = Json.createArrayBuilder();

        buildParameters(parameters, ParameterType.PATH, parameterBuilder);
        buildParameters(parameters, ParameterType.HEADER, parameterBuilder);
        buildParameters(parameters, ParameterType.QUERY, parameterBuilder);
        buildParameters(parameters, ParameterType.FORM, parameterBuilder);

        if (method.getRequestBody() != null) {
            final JsonObjectBuilder requestBuilder = Json.createObjectBuilder().add("name", "body").add("in", "body")
                    .add("required", true).add("schema", schemaBuilder.build(method.getRequestBody()));
            if (!StringUtils.isBlank(method.getRequestBodyDescription()))
                requestBuilder.add("description", method.getRequestBodyDescription());
            parameterBuilder.add(requestBuilder);
        }
        return parameterBuilder;
    }

    private void buildParameters(final Set<MethodParameter> parameters, final ParameterType parameterType,
            final JsonArrayBuilder builder) {
        parameters.stream().filter(p -> p.getParameterType() == parameterType).sorted(parameterComparator()).forEach(e -> {
            final String swaggerParameterType = getSwaggerParameterType(parameterType);
            if (swaggerParameterType != null) {
                final JsonObjectBuilder paramBuilder = schemaBuilder.build(e.getType()).add("name", e.getName())
                        .add("in", swaggerParameterType).add("required", e.getDefaultValue() == null);
                if (!StringUtils.isBlank(e.getDescription())) {
                    paramBuilder.add("description", e.getDescription());
                }
                if (!StringUtils.isBlank(e.getDefaultValue())) {
                    paramBuilder.add("default", e.getDefaultValue());
                }
                builder.add(paramBuilder);
            }
        });
    }

    private JsonObjectBuilder buildResponses(final ResourceMethod method) {
        final JsonObjectBuilder responses = Json.createObjectBuilder();

        method.getResponses().entrySet().stream().sorted(mapKeyComparator()).forEach(e -> {
            final JsonObjectBuilder headers = Json.createObjectBuilder();
            e.getValue().getHeaders().stream().sorted()
                    .forEach(h -> headers.add(h, Json.createObjectBuilder().add("type", "string")));

            final JsonObjectBuilder response = Json.createObjectBuilder().add("description", Optional
                    .ofNullable(Response.Status.fromStatusCode(e.getKey())).map(Response.Status::getReasonPhrase).orElse(""))
                    .add("headers", headers);

            if (e.getValue().getResponseBody() != null) {
                final JsonObject schema = schemaBuilder.build(e.getValue().getResponseBody()).build();
                if (!schema.isEmpty())
                    response.add("schema", schema);
            }

            responses.add(e.getKey().toString(), response);
        });

        return responses;
    }

    private void appendDefinitions() {
        builder.add("definitions", schemaBuilder.getDefinitions());
    }

    private static String getSwaggerParameterType(final ParameterType parameterType) {
        switch (parameterType) {
        case QUERY:
            return "query";
        case PATH:
            return "path";
        case HEADER:
            return "header";
        case FORM:
            return "formData";
        default:
            // TODO handle others (possible w/ Swagger?)
            return null;
        }
    }

    private static byte[] serialize(final JsonObject jsonObject) {
        try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final Map<String, ?> config = singletonMap(JsonGenerator.PRETTY_PRINTING, true);
            final JsonWriter jsonWriter = Json.createWriterFactory(config).createWriter(output);
            jsonWriter.write(jsonObject);
            jsonWriter.close();

            return output.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Could not write Swagger output", e);
        }
    }

    private static class SchemaBuilder {

        private final Map<String, Pair<String, JsonObject>> jsonDefinitions = new HashMap<>();

        private final Map<TypeIdentifier, TypeRepresentation> typeRepresentations;

        SchemaBuilder(final Map<TypeIdentifier, TypeRepresentation> typeRepresentations) {
            this.typeRepresentations = typeRepresentations;
        }

        JsonObjectBuilder build(final TypeIdentifier identifier) {
            final SchemaBuilder.SwaggerType type = toSwaggerType(identifier.getType());
            switch (type) {
            case BOOLEAN:
            case INTEGER:
            case NUMBER:
            case NULL:
            case STRING:
                final JsonObjectBuilder builder = Json.createObjectBuilder();
                addPrimitive(builder, type);
                return builder;
            }

            final JsonObjectBuilder builder = Json.createObjectBuilder();

            final TypeRepresentationVisitor visitor = new TypeRepresentationVisitor() {

                private boolean inCollection = false;

                @Override
                public void visit(final TypeRepresentation.ConcreteTypeRepresentation representation) {
                    final JsonObjectBuilder nestedBuilder = inCollection ? Json.createObjectBuilder() : builder;
                    add(nestedBuilder, representation);

                    if (inCollection) {
                        builder.add("items", nestedBuilder.build());
                    }
                }

                @Override
                public void visitStart(final TypeRepresentation.CollectionTypeRepresentation representation) {
                    builder.add("type", "array");
                    inCollection = true;
                }

                @Override
                public void visitEnd(final TypeRepresentation.CollectionTypeRepresentation representation) {
                    builder.add("type", "array");
                    inCollection = true;
                }

                @Override
                public void visit(final TypeRepresentation.EnumTypeRepresentation representation) {
                    builder.add("type", "string");
                    if (!representation.getEnumValues().isEmpty()) {
                        final JsonArrayBuilder array = representation.getEnumValues().stream().sorted()
                                .collect(Json::createArrayBuilder, JsonArrayBuilder::add, JsonArrayBuilder::add);
                        builder.add("enum", array);
                    }
                }
            };

            final TypeRepresentation representation = typeRepresentations.get(identifier);
            if (representation == null)
                builder.add("type", "object");
            else
                representation.accept(visitor);
            return builder;
        }

        JsonObject getDefinitions() {
            final JsonObjectBuilder builder = Json.createObjectBuilder();
            jsonDefinitions.entrySet().stream().sorted(mapKeyComparator())
                    .forEach(e -> builder.add(e.getKey(), e.getValue().getRight()));
            return builder.build();
        }

        private void add(final JsonObjectBuilder builder, final TypeRepresentation.ConcreteTypeRepresentation representation) {
            final SchemaBuilder.SwaggerType type = toSwaggerType(representation.getIdentifier().getType());
            switch (type) {
            case BOOLEAN:
            case INTEGER:
            case NUMBER:
            case NULL:
            case STRING:
                addPrimitive(builder, type);
                return;
            }

            addObject(builder, representation.getIdentifier(), representation.getProperties());
        }

        private void addObject(final JsonObjectBuilder builder, final TypeIdentifier identifier,
                final Map<String, TypeIdentifier> properties) {
            final String definition = buildDefinition(identifier.getName());

            if (jsonDefinitions.containsKey(definition)) {
                builder.add("$ref", "#/definitions/" + definition);
                return;
            }

            // reserve definition
            jsonDefinitions.put(definition, Pair.of(identifier.getName(), Json.createObjectBuilder().build()));

            final JsonObjectBuilder nestedBuilder = Json.createObjectBuilder();

            properties.entrySet().stream().sorted(mapKeyComparator())
                    .forEach(e -> nestedBuilder.add(e.getKey(), build(e.getValue())));
            jsonDefinitions.put(definition,
                    Pair.of(identifier.getName(), Json.createObjectBuilder().add("properties", nestedBuilder).build()));

            builder.add("$ref", "#/definitions/" + definition);
        }

        private void addPrimitive(final JsonObjectBuilder builder, final SchemaBuilder.SwaggerType type) {
            builder.add("type", type.toString());
        }

        private String buildDefinition(final String typeName) {
            final String definition = typeName.startsWith(TypeIdentifier.DYNAMIC_TYPE_PREFIX) ? "JsonObject"
                    : typeName.substring(typeName.lastIndexOf('/') + 1, typeName.length() - 1);

            final Pair<String, JsonObject> containedEntry = jsonDefinitions.get(definition);
            if (containedEntry == null || containedEntry.getLeft() != null && containedEntry.getLeft().equals(typeName))
                return definition;

            if (!definition.matches("_\\d+$"))
                return definition + "_2";

            final int separatorIndex = definition.lastIndexOf('_');
            final int index = Integer.parseInt(definition.substring(separatorIndex + 1));
            return definition.substring(0, separatorIndex + 1) + (index + 1);
        }

        private static SchemaBuilder.SwaggerType toSwaggerType(final String type) {
            if (INTEGER_TYPES.contains(type))
                return SchemaBuilder.SwaggerType.INTEGER;

            if (DOUBLE_TYPES.contains(type))
                return SchemaBuilder.SwaggerType.NUMBER;

            if (BOOLEAN.equals(type) || PRIMITIVE_BOOLEAN.equals(type))
                return SchemaBuilder.SwaggerType.BOOLEAN;

            if (STRING.equals(type))
                return SchemaBuilder.SwaggerType.STRING;

            return SchemaBuilder.SwaggerType.OBJECT;
        }

        private enum SwaggerType {
            ARRAY,
            BOOLEAN,
            INTEGER,
            NULL,
            NUMBER,
            OBJECT,
            STRING;

            @Override
            public String toString() {
                return super.toString().toLowerCase();
            }
        }

    }

    enum SwaggerScheme {

        HTTP,
        HTTPS,
        WS,
        WSS

    }

    public static class SwaggerOptions {

        public static final String DOMAIN = "domain";

        public static final String SWAGGER_SCHEMES = "swaggerSchemes";

        public static final String RENDER_SWAGGER_TAGS = "renderSwaggerTags";

        public static final String SWAGGER_TAGS_PATH_OFFSET = "swaggerTagsPathOffset";

        public static final String JSON_PATCH = "jsonPatch";

        private static final String DEFAULT_DOMAIN = "";

        private static final Set<SwaggerScheme> DEFAULT_SCHEMES = EnumSet.of(SwaggerScheme.HTTP);

        private static final boolean DEFAULT_RENDER_TAGS = false;

        private static final int DEFAULT_TAGS_PATH_OFFSET = 0;

        private String domain = DEFAULT_DOMAIN;

        private Set<SwaggerScheme> schemes = DEFAULT_SCHEMES;

        private boolean renderTags = DEFAULT_RENDER_TAGS;

        private int tagsPathOffset = DEFAULT_TAGS_PATH_OFFSET;

        private JsonPatch jsonPatch;

        String getDomain() {
            return domain;
        }

        Set<SwaggerScheme> getSchemes() {
            return schemes;
        }

        boolean isRenderTags() {
            return renderTags;
        }

        int getTagsPathOffset() {
            return tagsPathOffset;
        }

        JsonPatch getJsonPatch() {
            return jsonPatch;
        }

        void configure(final Map<String, String> config) {
            if (config.containsKey(SWAGGER_TAGS_PATH_OFFSET)) {
                int swaggerTagsPathOffset = Integer.parseInt(config.get(SWAGGER_TAGS_PATH_OFFSET));

                if (swaggerTagsPathOffset < 0) {
                    System.err.println("Please provide positive integer number for option --swaggerTagsPathOffset\n");
                    throw new IllegalArgumentException(
                            "Please provide positive integer number for option --swaggerTagsPathOffset");
                }

                tagsPathOffset = swaggerTagsPathOffset;
            }

            if (config.containsKey(DOMAIN)) {
                domain = config.get(DOMAIN);
            }

            if (config.containsKey(SWAGGER_SCHEMES)) {
                schemes = extractSwaggerSchemes(config.get(SWAGGER_SCHEMES));
            }

            if (config.containsKey(RENDER_SWAGGER_TAGS)) {
                renderTags = Boolean.parseBoolean(config.get(RENDER_SWAGGER_TAGS));
            }

            if (config.containsKey(JSON_PATCH)) {
                jsonPatch = readPatch(config.get(JSON_PATCH));
            }
        }

        private Set<SwaggerScheme> extractSwaggerSchemes(final String schemes) {
            return Stream.of(schemes.split(",")).map(this::extractSwaggerScheme)
                    .collect(() -> EnumSet.noneOf(SwaggerScheme.class), Set::add, Set::addAll);
        }

        private SwaggerScheme extractSwaggerScheme(final String scheme) {
            switch (scheme.toLowerCase()) {
            case "http":
                return SwaggerScheme.HTTP;
            case "https":
                return SwaggerScheme.HTTPS;
            case "ws":
                return SwaggerScheme.WS;
            case "wss":
                return SwaggerScheme.WSS;
            default:
                throw new IllegalArgumentException("Unknown swagger scheme " + scheme);
            }
        }

        private static JsonPatch readPatch(final String patchFile) {
            try {
                final JsonArray patchArray = Json.createReader(Files.newBufferedReader(Paths.get(patchFile))).readArray();
                return Json.createPatchBuilder(patchArray).build();
            } catch (Exception e) {
                LogProvider.error("Could not read JSON patch from the specified location, reason: " + e.getMessage());
                LogProvider.error("Patch won't be applied");
                LogProvider.debug(e);
                return null;
            }
        }
    }
}
