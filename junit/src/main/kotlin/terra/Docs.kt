package terra

import io.skodjob.MdGenerator
import io.skodjob.annotations.SuiteDoc
import io.skodjob.annotations.TestDoc
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Test documentation, generated from the annotations the tests already carry.
 *
 * The annotations and the Markdown writer are skodjob's test-metadata-generator,
 * which ships as a Maven plugin. Only the Mojo is Maven: it assembles a classpath
 * Gradle already has, and finds classes by walking a `src/test/java` tree, which
 * is no use to Kotlin sources whose file name need not match the class. Underneath
 * that, `MdGenerator` is plain reflection over RUNTIME-retention annotations and
 * takes a `Class<?>`. So the plugin is not ported — it is called, from here, with
 * the test runtime classpath Gradle assembled and the compiled classes on it.
 *
 * Same shape as [Discover]: classpath scanning, no environment, no containers.
 *
 * Usage: Docs <docs-dir> <test-classes-dir>[:<dir>...]
 */
object Docs {

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: Docs <docs-dir> <test-classes-dir>[:<dir>...]" }

        // MdGenerator concatenates rather than resolves, so the trailing separator
        // is load-bearing — the Mojo appends it too.
        val docs = args[0].trimEnd('/') + "/"

        val roots = args.drop(1)
            .flatMap { it.split(File.pathSeparator) }
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
            .filter { Files.isDirectory(it) }

        var documented = 0
        for (root in roots) {
            val classFiles = Files.walk(root).use { it.toList() }
                .filter { it.extension == "class" }
                .map { root.relativize(it).invariantSeparatorsPathString.removeSuffix(".class") }
                // Nested and synthetic classes carry no docs of their own, and Kotlin
                // emits a lot of them.
                .filter { '$' !in it }
                .sorted()

            for (path in classFiles) {
                // initialize = false: loading a test class must not run its
                // companion-object initialisers. Reading annotations does not need it.
                val klass = runCatching {
                    Class.forName(path.replace('/', '.'), false, javaClass.classLoader)
                }.getOrElse {
                    System.err.println("terra: skipping $path — $it")
                    continue
                }

                if (klass.isAnnotationPresent(SuiteDoc::class.java) ||
                    klass.declaredMethods.any { it.isAnnotationPresent(TestDoc::class.java) }
                ) {
                    // Mirrors the package structure, so `labels/` resolves by the
                    // relative depth MdGenerator computes from this path.
                    MdGenerator.generate(klass, docs, "$path.md")
                    documented++
                }
            }
        }

        // Back-links: rewrites each hand-written docs/labels/<label>.md with the
        // tests that carry it. Silently does nothing if there is no labels dir.
        MdGenerator.updateLinksInLabels(docs)

        println("terra: documented $documented test ${if (documented == 1) "class" else "classes"} into $docs")
    }
}
