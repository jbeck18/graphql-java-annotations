package schemabuilder.processor

import com.apollographql.federation.graphqljava.Federation
import com.apollographql.federation.graphqljava._Entity
import graphql.GraphQL
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentation
import graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentationOptions
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaGenerator
import schemabuilder.annotations.documentation.Stable
import schemabuilder.processor.pipelines.building.WiringBuilder
import schemabuilder.processor.pipelines.parsing.ParsedResults
import schemabuilder.processor.schema.SchemaParser
import schemabuilder.processor.wiring.DefaultInstanceFetcher
import schemabuilder.processor.wiring.InstanceFetcher
import java.io.IOException
import java.util.*
import java.util.stream.Collectors
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType

@Stable
class GraphQLBuilder private constructor(
        fetcher: InstanceFetcher,
        additionalClasses: Set<Class<*>>,
        basePackageForClasses: String,
        schemaFileExtension: String,
        instrumentation: ChainedInstrumentation,
        private val isFederated: Boolean
) {

    private val builder: WiringBuilder = WiringBuilder.withOptions(basePackageForClasses, additionalClasses, fetcher)
    private val schemaParser: SchemaParser = SchemaParser(schemaFileExtension)

    private val instrumentation: ChainedInstrumentation

    @Throws(IOException::class)
    fun generateGraphQL(): GraphQL {
        val typeRegistry = schemaParser.registry
        val runtimeWiring = builder.buildWiring().build()
        val schemaGenerator = SchemaGenerator()
        var schema = schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring)

        if (isFederated) {
           schema = transformForFederation(schema)
        }

        return GraphQL.newGraphQL(schema)
                .instrumentation(instrumentation)
                .build()
    }

    private fun transformForFederation(schema: GraphQLSchema): GraphQLSchema {
        return Federation.transform(schema).resolveEntityType { env ->
            ParsedResults.types.forEach { (clazz, data) ->
                val t: Any = env.getObject()
                if (t::class.starProjectedType.isSubtypeOf(clazz.starProjectedType)) {
                    return@resolveEntityType env.schema.getObjectType(data.typename)
                }
            }
            null
        }.fetchEntities { env ->
            env.getArgument<List<Map<String, Any>>>(_Entity.argumentName)
                .stream()
                .map { values ->
                    if ("Product" == values["__typename"]) {
                        val upc = values["upc"]
                        if (upc is String) {
                            return@map null
                        }
                    }

                    ParsedResults.types.forEach { (clazz, data) ->
                        if (data.typename == values["__typename"]) {
                            val id = values[data.idField]
                            if (id is String) {
                                return@map data.loader.load(id)
                            }
                        }
                    }

                    null
                }.collect(Collectors.toList())
        }.build()
    }

    class Builder {
        private var fetcher: InstanceFetcher = DefaultInstanceFetcher()
        private val additionalClasses: MutableSet<Class<*>>
        private var basePackageForClasses: String = ""
        private var schemaFileExtension: String
        private var instrumentation: ChainedInstrumentation
        private var maxQueryCost = 100
        private var isFederated: Boolean = false

        fun setInstanceFetcher(injector: InstanceFetcher): Builder {
            fetcher = injector
            return this
        }

        fun addClass(clazz: Class<*>): Builder {
            additionalClasses.add(clazz)
            return this
        }

        fun setBasePackageForClasses(basePackage: String): Builder {
            basePackageForClasses = basePackage
            return this
        }

        fun setSchemaFileExtension(extension: String): Builder {
            schemaFileExtension = extension
            return this
        }

        fun setInstrumentation(instrumentation: ChainedInstrumentation): Builder {
            this.instrumentation = instrumentation
            return this
        }

        fun setMaxQueryCost(maxQueryCost: Int): Builder {
            this.maxQueryCost = maxQueryCost
            return this
        }

        fun setIsFederated(isFederated: Boolean): Builder {
            this.isFederated = isFederated
            return this
        }

        fun build(): GraphQLBuilder {
            return GraphQLBuilder(
                    fetcher,
                    additionalClasses,
                    basePackageForClasses,
                    schemaFileExtension,
                    instrumentation,
                    isFederated
            )
        }

        init {
            additionalClasses = HashSet()
            basePackageForClasses = ""
            schemaFileExtension = "graphqls"
            val insts: MutableList<Instrumentation> = ArrayList()
            insts.add(
                    DataLoaderDispatcherInstrumentation(
                            DataLoaderDispatcherInstrumentationOptions.newOptions()
                                    .includeStatistics(true)
                    )
            )
            instrumentation = ChainedInstrumentation(insts)
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        var maxQueryCost = 0
            private set

        @kotlin.jvm.JvmStatic
        fun newGraphQLBuilder(): Builder {
            return Builder()
        }

    }

    init {
        this.instrumentation = instrumentation
        Companion.maxQueryCost = maxQueryCost
    }
}