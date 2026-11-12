# drivesync merge test vectors

Each JSON file states one merge rule by example. `index.txt` lists the files.

- `tables`: the tables involved, each with a `mergeClass` (`LWW_FIELDS`, `APPEND_ONLY`, `LWW_ROW`)
  and optional `parents` (`column` holds the row id of a row in `table`).
- `changes`: field writes `{table, row, column, value, hlc}` and tombstones
  `{table, row, delete: true, hlc}`. `hlc` is `<wall millis>:<counter>:<replica id>`, ordered by
  wall, then counter, then replica id as a string. A value is `{"text": ...}`, `{"int": ...}`,
  `{"real": ...}`, `{"bytes": "<base64>"}` or `null`.
- `expected.rows`: table -> row id -> column -> value, for the rows an app would see.
  `expected.deleted`, `held`, `orphaned`: `table/rowId` strings.

A conforming implementation reaches `expected` when the changes are applied in the order given, in
reverse, and in any shuffled order with any change delivered more than once.
