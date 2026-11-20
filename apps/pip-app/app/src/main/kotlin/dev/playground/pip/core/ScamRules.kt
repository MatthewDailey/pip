package dev.playground.pip.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RuleSpec(val id: String, val label: String, val weight: Int, val pattern: String)

@Serializable
data class RuleSet(val alertThreshold: Int, val rules: List<RuleSpec>)

data class Assessment(val score: Int, val hits: List<RuleSpec>, val alert: Boolean) {
  val labels: List<String> get() = hits.map { it.label }
}

/** Weighted phrase rules. Each rule counts once per text, so repeated phrases don't inflate the score. */
class ScamRules(private val set: RuleSet) {
  private val compiled = set.rules.map { it to Regex(it.pattern, RegexOption.IGNORE_CASE) }

  fun assess(text: String): Assessment = combine(compiled.filter { (_, re) -> re.containsMatchIn(text) }.map { it.first })

  /** Scores a set of rule hits gathered across many texts, e.g. every line of one call. */
  fun combine(hits: Collection<RuleSpec>): Assessment {
    val unique = hits.distinctBy { it.id }
    val score = unique.sumOf { it.weight }
    return Assessment(score, unique, score >= set.alertThreshold)
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(source: String): ScamRules = ScamRules(json.decodeFromString(RuleSet.serializer(), source))
  }
}
