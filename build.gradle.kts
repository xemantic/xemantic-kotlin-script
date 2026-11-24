import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jreleaser.model.Active

plugins {
    base
    // applied by the modules, declared here so that they share the classes of the plugins
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.power.assert) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.xemantic.conventions)
}

allprojects {
    group = "com.xemantic.kotlin"
    repositories {
        mavenCentral()
    }
}

xemantic {
    description = "Kotlin script execution with LLM-friendly diagnostics"
    inceptionYear = "2026"
    applyAllConventions()
}

fun MavenPomDeveloperSpec.projectDevs() {
    developer {
        id = "morisil"
        name = "Kazik Pogoda"
        url = "https://github.com/morisil"
    }
}

versionCatalogUpdate {
    // preserve the manual, logically-grouped ordering of libs.versions.toml
    sortByKey = false
    keep {
        // kotlinTarget / javaTarget are plain version constants with no version.ref
        versions = setOf("kotlinTarget", "javaTarget", "asm")
        keepUnusedVersions = false
    }
    pin {
        // bumped together with Kotlin, see the comment on mavenResolver in libs.versions.toml
        versions = setOf("mavenResolver", "maven")
    }
}

// the values of the xemantic extension of the root project, for the publications of all the projects
val projectDescription = xemantic.description
val projectInceptionYear = xemantic.inceptionYear
val gitHubAccount = xemantic.gitHubAccount
val organizationName = xemantic.organization
val organizationUrl = xemantic.organizationUrl

subprojects {

    plugins.withId("com.vanniktech.maven.publish") {

        configure<MavenPublishBaseExtension> {

            signAllPublications()

            publishToMavenCentral(automaticRelease = true)

            pom {

                name = project.name
                description = project.description ?: projectDescription
                inceptionYear = projectInceptionYear
                url = "https://github.com/$gitHubAccount/${rootProject.name}"

                organization {
                    name = organizationName
                    url = organizationUrl
                }

                licenses {
                    license {
                        name = "GNU Affero General Public License v3.0"
                        url = "https://www.gnu.org/licenses/agpl-3.0.txt"
                        distribution = "https://www.gnu.org/licenses/agpl-3.0.txt"
                    }
                }

                scm {
                    url = "https://github.com/$gitHubAccount/${rootProject.name}"
                    connection = "scm:git:git://github.com/$gitHubAccount/${rootProject.name}.git"
                    developerConnection = "scm:git:ssh://git@github.com/$gitHubAccount/${rootProject.name}.git"
                }

                ciManagement {
                    system = "GitHub"
                    url = "https://github.com/$gitHubAccount/${rootProject.name}/actions"
                }

                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/$gitHubAccount/${rootProject.name}/issues"
                }

                developers {
                    projectDevs()
                }

            }

        }

    }

}

val releaseAnnouncementSubject = """🚀 ${rootProject.name} $version has been released!"""
val releaseAnnouncement = """
$releaseAnnouncementSubject

${xemantic.description}

${xemantic.releasePageUrl}
""".trim()

jreleaser {

    announce {
        webhooks {
            create("discord") {
                active = Active.ALWAYS
                message = releaseAnnouncement
                messageProperty = "content"
                structuredMessage = true
            }
        }
        linkedin {
            active = Active.ALWAYS
            subject = releaseAnnouncementSubject
            message = releaseAnnouncement
        }
        bluesky {
            active = Active.ALWAYS
            status = releaseAnnouncement
        }
    }

}
