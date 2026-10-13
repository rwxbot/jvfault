package com.jvfault.graphql;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GraphQL 端点 - Schema-first：SDL + RuntimeWiring 装配执行器。
 *
 * @since v0.7.0 (2021)
 * @author jvfault team
 */
public class GraphQLEndpoint {

    private static final Logger log = LoggerFactory.getLogger(GraphQLEndpoint.class);

    private final GraphQL graphql;

    public GraphQLEndpoint(String sdl, RuntimeWiring wiring) {
        SchemaParser parser = new SchemaParser();
        TypeDefinitionRegistry registry = parser.parse(sdl);
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
        this.graphql = GraphQL.newGraphQL(schema).build();
        log.info("GraphQL schema ready");
    }

    /**
     * 执行查询（JSON 风格结果：data/errors）。
     */
    public Map<String, Object> execute(String query, Map<String, Object> variables, Object context) {
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables != null ? variables : new LinkedHashMap<String, Object>())
                .graphQLContext(toContextMap(context))
                .build();
        graphql.ExecutionResult result = graphql.execute(input);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", result.isDataPresent() ? result.getData() : null);
        if (!result.getErrors().isEmpty()) {
            response.put("errors", result.getErrors());
        }
        return response;
    }

    public Map<String, Object> execute(String query) {
        return execute(query, null, null);
    }

    /**
     * 异步执行。
     */
    public CompletableFuture<Map<String, Object>> executeAsync(String query, Map<String, Object> variables) {
        return CompletableFuture.supplyAsync(() -> execute(query, variables, null));
    }

    /** context 语义保持原样：Map 直接透传，其它对象包成 {"value": ...} */
    @SuppressWarnings("unchecked")
    private static Map<?, Object> toContextMap(Object context) {
        if (context == null) {
            return new LinkedHashMap<>();
        }
        if (context instanceof Map<?, ?> m) {
            return (Map<?, Object>) m;
        }
        return Map.of("value", context);
    }
}
