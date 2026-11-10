# yetibot-kroki

[Yetibot](https://github.com/yetibot/yetibot) module for
[kroki.io](https://kroki.io).

## Commands

```text
!kroki
!kroki types
!kroki info mermaid
!kroki mermaid graph TD; A-->B;
!kroki render d2 --option layout=tala -- x -> y
!kroki render graphviz --format pdf -- digraph { A -> B }
```

Use a complete Markdown code fence for source containing pipes, backticks or
`$(...)`. Fenced blocks are literal in the updated core parser. Multiline and
piped text are preserved; only one complete outer fence is removed. Options
precede source; `--` ends options. Repeat `--option KEY=VALUE` for scalar engine
options. Native agents pass an options object, not command text.

Auto requests PNG then SVG through Kroki's Accept negotiation, without a list
of SVG-only engines. Explicit formats are `png`, `svg`, `jpeg`, `pdf`, `txt`,
`utxt`, and `base64`; support depends on the selected engine. SVG/PDF/text are
downloads, not inline Discord image galleries. Rendering POSTs source once and
only reports success after validating and retaining the returned artifact.

`types` discovers registered engine names, aliases and versions from `/health`,
not a hardcoded allowlist. Successful metadata is cached for five minutes;
discovery failures may return explicitly marked stale metadata. Catalog failure
never blocks rendering. `/health` is not a format/options schema or proof that
a particular diagram will render. See [Kroki options](https://docs.kroki.io/kroki/setup/diagram-options/).

The operator may set `{:yetibot {:kroki {:url "https://kroki.example"}}}` or
`YETIBOT_KROKI_URL`; the default remains `https://kroki.io`. Models cannot change
the endpoint. HTTP is supported for operator-managed internal services; HTTPS
is recommended otherwise. Requests have time/size bounds, no redirects and no
automatic retries. Sources are limited to 256 KiB UTF-8; artifacts to 8 MiB.

**Privacy and lifetime:** the configured renderer receives the source. POST
keeps it out of artifact URLs; it does not make the renderer private. Stored
artifacts are public bearer URLs in Yetibot's bounded in-memory media store.
They disappear on eviction/restart. Do not send credentials or treat these URLs
as private or permanent storage.

## Result contract and integration

Rendering returns `:result/value`, artifact `:result/data`, and an authenticated
reviewed `:result/public-data` projection with type, actual format/MIME, byte
count, URL, ID and `:ephemeral true`. Binary bytes, source, encoded source URLs,
options and upstream headers are absent. Catalogs preserve corresponding public
rows through collection pipelines, including freshness metadata.

Errors return `:result/error`, structured raw diagnostics and reviewed
`:result/public-error`. Known HTTP failures distinguish invalid source/options,
unsupported type/format, size limits and service failures. Safe numeric
line/column locations are retained; arbitrary upstream prose is not published.

The shared API is `yetibot-kroki.client/types!`, `validate-render!`, and `render!`.
The legacy `encode-for-kroki`, `kroki-url` and `graphviz-url` helpers remain for
external callers; their unchecked source-bearing URLs are not render success.

This release needs core's generated SVG/PDF/text download routes, MIME-aware
Discord uploads and literal fenced parser support. Adopt that core release
before this plugin, then adopt the actual published plugin version in Yetibot.
Do not substitute an invented version for an unpublished artifact.

## Tests

```sh
lein midje :all
```

Client/command regressions use offline transport fixtures, including future
engine discovery, negotiation, source preservation, reviewed data and safe
errors. A public Kroki request is not part of the automated suite.

## License

Copyright © 2020 Trevor Hartman

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
