# Madjo semantic dictionary JSON v2

The standalone dictionary backup is UTF-8 JSON. Its format identifier remains
`thes-du-palais-madjo-dictionary`; version 2 adds the required `associations`
array while retaining all concepts, expressions, hierarchical relations,
business links, active states, stable identifiers, and deleted-concept
tombstones from v1.

## Shape

The root contains `format`, `version`, `concepts`, `expressions`, `relations`,
and `associations`. Objects are exported in deterministic order (stable IDs
for concepts, concept then ID for expressions, and source/target/ID for links).
JSON object field order is stable in the application exporter as well.

An association has this shape:

```json
{"id":"stable-id","sourceConceptId":"source-id","targetConceptId":"target-id","weightPercent":50,"active":true}
```

Associations are directed and do not imply their reverse. Supported weights are
25 (Faible), 50 (Moyenne, the default), and 75 (Forte). They express relative
ranking influence, not statistical probabilities. An association target must
be a compatible concept mapped to a real tea characteristic. Neither an
association nor import creates or changes tea references.

## Import behavior

Import is a validated, transactional replacement of semantic dictionary data
only. Invalid associations, dangling IDs, incompatible mappings, and unsupported
versions are rejected before replacing the current dictionary. Tea sheets,
references, tasting records, stock, and articles are outside the replacement.
Version 1 remains supported: it has no association array, interpreted as an
empty association set during full replacement. Version 1 files are never
rewritten in place; the next explicit export emits v2.

Version 2 is forward-compatible only through an explicit format-version
implementation; future versions are rejected by current releases.
