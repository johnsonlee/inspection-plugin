package org.jetbrains.intellij

import com.intellij.codeInspection.InspectionProfileEntry
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Assert.*
import org.gradle.testkit.runner.TaskOutcome.*
import org.jetbrains.java.generate.inspection.ClassHasNoToStringMethodInspection
import org.jetbrains.kotlin.idea.inspections.RedundantVisibilityModifierInspection
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName


class InspectionTest {
    @Rule
    @JvmField
    val testProjectDir = TemporaryFolder()

    private lateinit var buildFile: File

    private lateinit var inspectionsFile: File

    private lateinit var sourceKotlinFile: File

    private lateinit var sourceJavaFile: File

    @Before
    fun setup() {
        buildFile = testProjectDir.newFile("build.gradle")
        testProjectDir.newFolder("config", "inspections")
        inspectionsFile = testProjectDir.newFile("config/inspections/inspections.xml")
        testProjectDir.newFolder("src", "main", "kotlin")
        testProjectDir.newFolder("src", "main", "java")
        sourceKotlinFile = testProjectDir.newFile("src/main/kotlin/main.kt")
        sourceJavaFile = testProjectDir.newFile("src/main/java/Main.java")
    }

    @Test
    fun testHelloWorldTask() {
        val buildFileContent = "task helloWorld {" +
                               "    doLast {" +
                               "        println 'Hello world!'" +
                               "    }" +
                               "}"
        writeFile(buildFile, buildFileContent)

        val result = GradleRunner.create()
                .withProjectDir(testProjectDir.root)
                .withArguments("helloWorld")
                .build()

        assertTrue(result.output.contains("Hello world!"))
        assertEquals(result.task(":helloWorld").outcome, SUCCESS)
    }

    private fun generateBuildFile(kotlinNeeded: Boolean, kotlinVersion: String = "1.1.4"): String {
                return StringBuilder().apply {
            val kotlinGradleDependency = if (kotlinNeeded) """
    dependencies {
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion"
    }
                """ else ""
            appendln("""
buildscript {
    repositories {
        mavenCentral()
    }
    $kotlinGradleDependency
}
                """)
            val kotlinPlugin = if (kotlinNeeded) "id 'org.jetbrains.kotlin.jvm' version '$kotlinVersion'" else ""
            appendln("""
plugins {
    id 'java'
    $kotlinPlugin
    id 'org.jetbrains.intellij.inspections'
}
                """)
            appendln("""
sourceSets {
    main {
        java {
            srcDirs = ['src']
        }
    }
}
                """)
            if (kotlinNeeded) {
                appendln("""
repositories {
    mavenCentral()
}

dependencies {
    compile "org.jetbrains.kotlin:kotlin-stdlib"
    compile "org.jetbrains.kotlin:kotlin-runtime"
}
                    """)
            }
        }.toString()
    }

    private fun assertInspectionBuild(
            expectedOutcome: TaskOutcome,
            vararg expectedDiagnostics: String
    ) {
        val result = GradleRunner.create()
                .withProjectDir(testProjectDir.root)
                .withArguments("--info", "--stacktrace", "inspectionsMain")
                .withPluginClasspath()
                .build()

        println(result.output)
        for (diagnostic in expectedDiagnostics) {
            assertTrue(diagnostic in result.output)
        }
        assertEquals(result.task(":inspectionsMain").outcome, expectedOutcome)
    }

    private fun writeFile(destination: File, content: String) {
        destination.bufferedWriter().use {
            it.write(content)
        }
    }

    private fun generateInspectionTags(
            tagName: String,
            inspections: List<KClass<out InspectionProfileEntry>>
    ): String {
        return StringBuilder().apply {
            appendln("    <${tagName}s>")
            for (inspectionClass in inspections) {
                appendln("        <$tagName class = \"${inspectionClass.jvmName}\"/>")
            }
            appendln("    </${tagName}s>")
        }.toString()
    }

    private fun generateInspectionFile(
            errors: List<KClass<out InspectionProfileEntry>> = emptyList(),
            warnings: List<KClass<out InspectionProfileEntry>> = emptyList(),
            infos: List<KClass<out InspectionProfileEntry>> = emptyList()
    ): String {
        return StringBuilder().apply {
            appendln("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>")
            appendln("<inspections>")
            appendln(generateInspectionTags("error", errors))
            appendln(generateInspectionTags("warning", warnings))
            appendln(generateInspectionTags("info", infos))
            appendln("</inspections>")
        }.toString()
    }

    @Test
    fun testInspectionConfigurationJava() {
        val buildFileContent = generateBuildFile(kotlinNeeded = false)
        writeFile(buildFile, buildFileContent)
        val inspectionsFileContent = generateInspectionFile(
                warnings = listOf(ClassHasNoToStringMethodInspection::class)
        )
        writeFile(inspectionsFile, inspectionsFileContent)
        writeFile(sourceJavaFile, "public class Main { private int x = 42; }")

        assertInspectionBuild(
                SUCCESS,
                "Main.java:1:14: Class 'Main' does not override 'toString()' method"
        )
    }

    @Test
    fun testInspectionConfigurationKotlin() {
        val buildFileContent = generateBuildFile(kotlinNeeded = true)
        writeFile(buildFile, buildFileContent)
        val inspectionsFileContent = generateInspectionFile(
                warnings = listOf(RedundantVisibilityModifierInspection::class)
        )
        writeFile(inspectionsFile, inspectionsFileContent)
        writeFile(sourceKotlinFile,
                """
public val x = 42

public val y = 13

                """)

        assertInspectionBuild(
                SUCCESS,
                "main.kt:2:1: Redundant visibility modifier",
                "main.kt:4:1: Redundant visibility modifier"
        )
    }
}