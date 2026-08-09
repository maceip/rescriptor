package com.wasmo.identifiers

/**
 * The complete set of effects a Wasmo app can have on the world.
 *
 * Every entry names a family of mediated platform calls. Apps declare the families they need in
 * `wasmo-manifest.toml`; the OS holds them to that declaration at runtime and shows the same
 * vocabulary to the person who owns the computer.
 *
 * This is deliberately coarse. An app asks for 'http', not for 'http.fetch': method-level
 * identifiers are an implementation detail of the capability boundary, and a person deciding
 * whether to install an app should not have to read one.
 */
enum class Capability(
  /** The name used in `wasmo-manifest.toml` and in the OS's own tooling. */
  val id: String,

  /** A sentence completing "This app can ...", for humans choosing to trust an app. */
  val summary: String,

  /** What an allow list narrows this capability to, if anything. */
  val scope: CapabilityScope,
) {
  Clock(
    id = "clock",
    summary = "read the current time",
    scope = CapabilityScope.None,
  ),
  Random(
    id = "random",
    summary = "read cryptographically secure random bytes",
    scope = CapabilityScope.None,
  ),
  Http(
    id = "http",
    summary = "make outbound HTTP requests",
    scope = CapabilityScope.Url,
  ),
  ObjectStore(
    id = "object_store",
    summary = "read and write its own object storage",
    scope = CapabilityScope.ObjectKey,
  ),
  Downloader(
    id = "downloader",
    summary = "download URLs straight into its own object storage",
    scope = CapabilityScope.Url,
  ),
  Jobs(
    id = "jobs",
    summary = "enqueue and cancel its own background jobs",
    scope = CapabilityScope.QueueName,
  ),
  Sql(
    id = "sql",
    summary = "read and write its own SQL databases",
    scope = CapabilityScope.DatabaseName,
  ),
  ;

  companion object {
    private val byId: Map<String, Capability> = entries.associateBy { it.id }

    /** Every manifest capability name, in declaration order. */
    val ids: List<String> = entries.map { it.id }

    fun findById(id: String): Capability? = byId[id]
  }
}

/** What a capability's allow list constrains, if the capability can be narrowed at all. */
enum class CapabilityScope(
  /** How to describe one allow-list entry to a human, or null when there is no allow list. */
  val label: String?,
) {
  None(label = null),
  Url(label = "URL"),
  ObjectKey(label = "object key"),
  QueueName(label = "queue name"),
  DatabaseName(label = "database name"),
  ;

  val isScopable: Boolean
    get() = label != null
}
