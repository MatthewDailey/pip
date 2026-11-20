# showreel-to-rivc (WIP)

It converts the Kotlin showreel's
exported scenes and the set-piece motion drafts in `packages/pip-rive/sets/` into a rivc
project, so the showreel can play from a `.riv` instead of the Kotlin keyframe engine.

It is a standalone Gradle build that includes `../rivc` as a composite build. It is deliberately left out of
`turbo run build test`; try it with:

```sh
pnpm turbo run build:wip test:wip --filter @shady-pip/showreel-to-rivc
```
