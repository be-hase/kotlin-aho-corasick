package conventions

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish")
    id("conventions.dokka")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(
        project.group.toString(),
        project.name,
        project.version.toString(),
    )
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    afterEvaluate {
        pom {
            name = project.name
            description = project.description
            url = "https://github.com/be-hase/kotlin-aho-corasick"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/license/mit"
                }
            }
            developers {
                developer {
                    id = "be-hase"
                    name = "Ryosuke Hasebe"
                    email = "hsb.1014@gmail.com"
                }
            }
            scm {
                connection.set("scm:git:git://github.com/be-hase/kotlin-aho-corasick.git")
                developerConnection.set("scm:git:ssh://github.com:be-hase/kotlin-aho-corasick.git")
                url.set("https://github.com/be-hase/kotlin-aho-corasick")
            }
        }
    }
}
