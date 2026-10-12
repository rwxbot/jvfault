plugins {
    `java-platform`
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
            pom {
                name.set("jvfault-${project.name}")
                description.set("jvfault Framework BOM: align versions of all published modules")
                url.set("https://github.com/rwxbot/jvfault")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

dependencies {
    constraints {
        api(project(":core"))
        api(project(":aop"))
        api(project(":config"))
        api(project(":logging"))
        api(project(":validation"))
        api(project(":exception"))
        api(project(":scheduling"))
        api(project(":cache"))
        api(project(":tracing"))
        api(project(":metrics"))
        api(project(":plugin"))
        api(project(":apt"))
        api(project(":microservices"))
        api(project(":test"))
        api(project(":tests"))
        api(project(":web"))
        api(project(":platform-servlet"))
        api(project(":platform-reactive"))
        api(project(":websocket"))
        api(project(":sse"))
        api(project(":openapi"))
        api(project(":graphql"))
        api(project(":security"))
        api(project(":transport-tcp"))
        api(project(":transport-grpc"))
        api(project(":transport-kafka"))
        api(project(":transport-redis"))
        api(project(":transport-nats"))
        api(project(":transport-rmq"))
        api(project(":transport-mqtt"))
        api(project(":aot"))
        api(project(":native"))
        api(project(":virtualthreads"))
        api(project(":ai"))
        api(project(":rag"))
        api(project(":mcp"))
        api(project(":compliance"))
        api(project(":migration"))
        api(project(":ops"))
        api(project(":spring-boot-starter"))
    }
}
