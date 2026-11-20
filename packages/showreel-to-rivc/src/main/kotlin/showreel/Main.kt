package showreel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rivc.ProjectSource
import java.io.File
import kotlin.system.exitProcess

/** What the conversion covered and what it couldn't express; written next to the project as `showreel.report.json`. */
class Report {
    var pipRig = ""
    var pipInputs: List<Pair<String, String>> = emptyList()
    val pipGaps = mutableListOf<String>()
    var actorsConverted = 0
    var textsConverted = 0
    var instances = 0
    val actorRules = mutableListOf<String>()
    val unmatched = mutableListOf<String>()
    val texts = mutableListOf<String>()
    val textGaps = mutableListOf<String>()
    val masks = mutableListOf<String>()
    var cues = 0
    var events = 0
    var tracks = 0
    var keys = 0
    val pieceGaps = LinkedHashMap<String, List<String>>()
    val compilerGaps = mutableListOf<String>()
    val unusedPieces = mutableListOf<String>()
    var rivBytes = 0

    fun json(): JsonObject {
        fun s(v: String) = JsonPrimitive(v)
        fun arr(l: List<String>) = JsonArray(l.map(::s))
        return JsonObject(linkedMapOf<String, JsonElement>(
            "pipRig" to s(pipRig),
            "pipInputs" to JsonObject(pipInputs.associate { it.first to s(it.second) }),
            "pipGaps" to arr(pipGaps),
            "coverage" to JsonObject(linkedMapOf(
                "mainTracks" to JsonPrimitive(tracks), "mainKeys" to JsonPrimitive(keys),
                "actorsAsPieces" to JsonPrimitive(actorsConverted), "actorsAsTexts" to JsonPrimitive(textsConverted),
                "unmatchedActors" to JsonPrimitive(unmatched.size), "nestedInstances" to JsonPrimitive(instances),
                "cues" to JsonPrimitive(cues), "events" to JsonPrimitive(events), "rivBytes" to JsonPrimitive(rivBytes),
            )),
            "actors" to arr(actorRules),
            "unmatched" to arr(unmatched),
            "texts" to arr(texts),
            "textGaps" to arr(textGaps),
            "masks" to arr(masks),
            "compilerGaps" to arr(compilerGaps),
            "pieceGaps" to JsonObject(pieceGaps.filterValues { it.isNotEmpty() }.mapValues { arr(it.value) }),
            "unusedPieces" to arr(unusedPieces),
        ))
    }
}

/** Gaps every set piece shares: things rivc's source format can't say yet. */
val GENERAL_GAPS = listOf(
    "Colour inputs (fx tints: heart, music notes, sparkle, spotlight): no colour state-machine input and no colour remap; pieces keep their SVG colours.",
    "data-text placeholders in set SVGs (banner, bubble, leeroy letters, maze score, scam card) aren't drawn by rivc; titles, bubbles and shouts are rivc texts on the main artboard instead.",
    "Instances can't be parented to a node, so Pip's feet pivot, panel offsets and Pip-box pieces are composed into each instance's own keyed transform.",
)

fun main(args: Array<String>) {
    val opt = { name: String -> args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } }
    val repo = File(opt("repo") ?: ".").absoluteFile.normalize()
    val rig = File(repo, "packages/pip-rive/pip/pip-inputs.json").takeIf { it.isFile } ?: File(repo, "packages/rivc/examples/spike/pip.json")
    val paths = Paths(
        repo = repo,
        export = File(opt("export") ?: File(repo, "apps/pip-app/app/build/showreel/showreel.json").path),
        pipRig = File(opt("pip") ?: rig.path).absoluteFile,
        out = File(opt("out") ?: File(repo, "packages/pip-rive/showreel/showreel.json").path).absoluteFile,
    )
    if (!paths.export.isFile) {
        System.err.println("no export at ${paths.export}; run ./gradlew -q testDebugUnitTest --tests '*ShowExporterTest*' first")
        exitProcess(2)
    }
    val c = Converter(paths, only = opt("scene"))
    c.writePieces()
    val project = c.build()
    paths.projectDir.mkdirs()
    File(paths.projectDir, "fonts").mkdirs()
    paths.font.copyTo(File(paths.projectDir, "fonts/Inter-Bold.ttf"), overwrite = true)
    if (paths.fontLicence.isFile) paths.fontLicence.copyTo(File(paths.projectDir, "fonts/Inter-OFL.txt"), overwrite = true)
    paths.notesFont.copyTo(File(paths.projectDir, "fonts/${Converter.NOTES}.ttf"), overwrite = true)
    if (paths.notesLicence.isFile) paths.notesLicence.copyTo(File(paths.projectDir, "fonts/NotoSansSymbols-OFL.txt"), overwrite = true)
    paths.out.writeText(Converter.compact.encodeToString(ProjectSource.serializer(), project) + "\n")
    println("wrote ${paths.out} (${paths.out.length()} bytes)")

    c.report.compilerGaps += GENERAL_GAPS
    c.report.unusedPieces += c.pieces.keys.filter { name -> project.artboards.none { it.source?.contains("$name.rivc.json") == true } }
    if ("--no-compile" !in args) {
        val bytes = rivc.compileFile(paths.out)
        val riv = File(paths.out.parentFile, paths.out.nameWithoutExtension + ".riv")
        riv.writeBytes(bytes)
        c.report.rivBytes = bytes.size
        println("compiled ${riv} (${bytes.size} bytes)")
    }
    val reportFile = File(paths.out.parentFile, paths.out.nameWithoutExtension + ".report.json")
    reportFile.writeText(kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), c.report.json()) + "\n")
    println("report ${reportFile}")
    val r = c.report
    println("tracks ${r.tracks} keys ${r.keys}; actors ${r.actorsConverted} as pieces, ${r.textsConverted} as texts, ${r.unmatched.size} unmatched; instances ${r.instances}; cues ${r.cues} -> ${r.events} events")
}
