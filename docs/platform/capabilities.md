Capabilities
============

An app declares what it needs. The OS holds it to that, and keeps the receipts.

Every effect a Wasmo app can have on the world crosses one boundary: the platform. There is no
other syscall surface, no ambient filesystem, no shared network stack. That makes it possible to do
something a conventional sandbox cannot — say, in one screen, exactly what an app is able to do
before you install it, and then prove afterwards what it actually did.


Declaring capabilities
----------------------

Capabilities go in `wasmo-manifest.toml`, one table per capability:

```toml
target = 'https://wasmo.com/sdk/1'
version = 1

[[capability]]
name = 'clock'

[[capability]]
name = 'sql'

[[capability]]
name = 'http'
allow = ['https://api.weather.test/v1/**']
```

That app can read the clock, use its own databases, and reach exactly one API. It cannot write to
object storage, enqueue jobs, or make any other outbound request — not because it promised not to,
but because those calls fail.

| `name`         | The app can                                        | `allow` narrows by |
|:---------------|:---------------------------------------------------|:-------------------|
| `clock`        | read the current time                               | —                  |
| `random`       | read cryptographically secure random bytes          | —                  |
| `http`         | make outbound HTTP requests                         | URL                |
| `object_store` | read and write its own object storage               | object key         |
| `downloader`   | download URLs straight into its own object storage  | URL                |
| `jobs`         | enqueue and cancel its own background jobs          | queue name         |
| `sql`          | read and write its own SQL databases                | database name      |

Omitting `allow`, or leaving it empty, asks for the whole capability.


Deny by default, once you opt in
--------------------------------

A manifest with no `[[capability]]` tables gets everything, exactly as apps did before this existed.
Declaring even one capability is the app's statement that the list is complete, and the OS then
denies everything not on it.

This is deliberate: it lets existing apps keep running while new ones are written least-privilege
first. A future `target` will make declaration mandatory.


Patterns
--------

An `allow` entry is a glob over one value — the URL being fetched, the key being read, the queue or
database being named.

* `*` matches any run of characters inside a path segment.
* A `**` segment matches any number of segments, including none.

Matching is case-sensitive and anchored at both ends. A pattern that does not describe a target
exactly denies it, because at a security boundary an ambiguous pattern has to fail closed.

| Pattern                 | Matches                        | Does not match                          |
|:------------------------|:-------------------------------|:----------------------------------------|
| `https://api.test/**`   | `https://api.test/v1/forecast` | `https://api.test.evil.test/v1`         |
| `https://api.test/**`   | `https://api.test`             | `https://api.test@evil.test/v1`         |
| `photos/**`             | `photos/2026/a.jpg`            | `photosets/a.jpg`                       |
| `photos/*.jpg`          | `photos/a.jpg`                 | `photos/2026/a.jpg`                     |

The near-misses in the right-hand column are the point. Each is a real way an allow list gets
defeated when patterns are matched loosely: a lookalike host, a userinfo prefix, a sibling prefix.


What a gate is
--------------

Only the calls that first hand a named resource to an app are checked against an allow list.
`sql.getOrCreate` is checked; the connection you open on that database, the statements you run on
that connection, and the columns you read from those results are not. They do not need to be —
there is no way to reach them except through the gate, so checking the gate is both necessary and
sufficient.

This keeps the check where a person can reason about it. "This app can use the `notes` database" is
a sentence someone can agree to. "This app may call `sql.row.getString` with column index 3" is not.


What happens on a denial
------------------------

A denied call is not silently dropped. It is:

* refused, so the app sees a `CapabilityDeniedException` naming the capability and the reason;
* written to the invocation's capability journal as a failed call, so a replay reproduces the
  denial without needing the policy that produced it — which may have changed since;
* appended to the invocation's hash-chained audit as a `cap.denied` entry.

A denial is evidence, not an error to be swallowed.


Seeing what an app did
----------------------

Each app serves the OS's own record on its own host, to the computer's owner only:

```shell
# What has this app done?
curl https://notes-alice.wasmo.com/.wasmo/invocations

# Export one complete record.
curl https://notes-alice.wasmo.com/.wasmo/invocations/$ID > invocation.json

# Re-run it against a sealed platform and compare.
curl -X POST https://notes-alice.wasmo.com/.wasmo/invocations/$ID/replay
```

`/.wasmo/**` is reserved by the OS and answered before anything reaches the app, so an app can
neither serve nor observe requests for its own audit record.

An exported record carries its own proof, and checking it needs no server:

```shell
moose audit verify invocation.json
moose audit show invocation.json
```

`verify` checks more than the hash chain. A chain alone only proves it is internally consistent —
anyone who can rewrite the database can recompute one. It also checks that the chain describes
*this* journal: every capability call in the same order, each committing to the value the journal
says was returned, plus the invocation's own input and outcome. Rewriting a stored result cannot be
hidden by recomputing the chain, because the journal and the chain would both have to be forged
consistently, and the head published at the time would still not match.

Run it on your own laptop. That is the point of the format.


Replay
------

`POST /.wasmo/invocations/{id}/replay` re-runs a recorded invocation with its journal standing in
for the outside world, on a platform where every live effect throws. Nothing is re-fetched, no email
is re-sent, no card is re-charged, and no database that has since moved on is consulted.

The report is one of:

* **deterministic** — the app made the same calls in the same order and returned the same bytes.
* **divergent** — with the reason: it asked for something different, made a call that was never
  recorded, skipped one that was, or returned a different answer.

A divergent report is not a replay bug. It says the app is no longer a function of its recorded
inputs: it read a clock the boundary does not mediate, kept state between requests, or depends on
something that was never in the journal.
