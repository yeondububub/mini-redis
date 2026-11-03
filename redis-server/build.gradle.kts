plugins {
    application
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.miniredis.server.MainKt")
}


