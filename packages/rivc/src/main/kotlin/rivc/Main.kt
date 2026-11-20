package rivc

import kotlinx.serialization.SerializationException
import rivc.anim.ClipPass
import rivc.anim.StateMachinePass
import rivc.spike.CompileException
import rivc.art.ArtPass
import rivc.project.ProjectPass
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage:
  rivc compile <pip.json> -o <out.riv>   compile a source (pip.json, or a project with artboards) to .riv
  rivc dump <file.riv>                   list every object, one per line
  rivc diff <a.riv> <b.riv>              compare two files object by object (exit 1 if they differ)
  rivc schema                            print the pinned runtime the schema came from"""

/** Compiles [source] (a pip.json, or a project with several `artboards`) to bytes. */
fun compileFile(source: File): ByteArray {
    val text = source.readText()
    val dir = source.absoluteFile.parentFile
    if (ProjectSource.isProject(text)) return ProjectPass().compile(ProjectSource.parse(text), dir).encode()
    return compileGraph(PipSource.parse(text), dir).encode()
}

/**
 * Builds the object graph for [src]; relative paths in it resolve against [baseDir].
 *
 * Stream order: Backboard; enums, the view model and its instance (before the artboard,
 * or the instance takes an artboard slot); the Artboard, naming its state machine as the
 * default; the art; events and interpolators; clips; the state machine.
 */
fun compileGraph(src: PipSource, baseDir: File, schema: Schema = Schema.bundled): RivGraph {
    val g = RivGraph(schema)
    g.add("Backboard")
    val sm = StateMachinePass(schema)
    val vm = sm.viewModel(g, src)

    // The state machine is created now so the artboard can name it as its default.
    val ab = src.artboard
    val artboard = g.create("Artboard")
    val machine = src.stateMachine?.let { g.create("StateMachine", owner = artboard) }
    g.add(artboard.apply {
        ArtPass.artboardProperties(this, ab)
        if (machine != null) this["defaultStateMachineId"] = machine
        if (vm.viewModel != null) this["viewModelId"] = vm.viewModel
    })

    val targets = ArtPass().emit(g, artboard, src, baseDir)
    val clips = ClipPass(schema).run(g, artboard, src, targets)
    if (machine != null) sm.stateMachine(g, machine, src, vm, clips)
    return g
}

fun main(args: Array<String>) {
    val code = try {
        run(args.toList())
    } catch (e: RivGraphException) {
        System.err.println("rivc: ${e.message}"); 1
    } catch (e: CompileException) {
        System.err.println("rivc: ${e.message}"); 1
    } catch (e: SerializationException) {
        System.err.println("rivc: bad source: ${e.message}"); 1
    } catch (e: RivFormatException) {
        System.err.println("rivc: ${e.message}"); 1
    } catch (e: IllegalArgumentException) {
        System.err.println("rivc: ${e.message}"); 1
    }
    exitProcess(code)
}

private fun run(args: List<String>): Int {
    fun usage(): Int { System.err.println(USAGE); return 2 }
    fun riv(path: String) = RivReader().read(File(path).readBytes())
    return when (args.firstOrNull()) {
        "compile" -> {
            val rest = args.drop(1)
            val o = rest.indexOf("-o")
            if (o < 0 || o + 1 >= rest.size) return usage()
            val out = File(rest[o + 1])
            val inputs = rest.filterIndexed { i, _ -> i != o && i != o + 1 }
            if (inputs.size != 1) return usage()
            val bytes = compileFile(File(inputs[0]))
            out.absoluteFile.parentFile?.mkdirs()
            out.writeBytes(bytes)
            println("wrote ${out.path}: ${bytes.size} bytes")
            0
        }
        "dump" -> {
            if (args.size != 2) return usage()
            print(RivDump.text(riv(args[1])))
            0
        }
        "diff" -> {
            if (args.size != 3) return usage()
            val d = RivDiff.diff(RivDump.lines(riv(args[1])), RivDump.lines(riv(args[2])))
            if (d.isEmpty()) {
                println("same: ${args[1]} and ${args[2]} have identical object streams")
                0
            } else {
                println("--- ${args[1]}\n+++ ${args[2]}")
                d.forEach(::println)
                1
            }
        }
        "schema" -> {
            val s = Schema.bundled
            println("rive-runtime ${s.runtimeCommit}, format ${s.formatMajor}.${s.formatMinor}, ${s.classNames.size} classes")
            0
        }
        else -> usage()
    }
}
