## 0.1.5 -- 14 Mar 2017

Added --focus option to vizdeps task.

## 0.1.4 -- 8 Mar 2017

[Closed Issues](https://github.com/walmartlabs/vizdeps/milestone/1?closed=1)

Add support for :managed-dependencies.

New task: vizconflicts, which iterates across the modules of a multi-module
project, and creates a graph showing dependencies and versions for all
artifact version conflicts.

The vizdeps task now accepts the -p / --prune option, which is used
when investigating version conflicts; --prune identifies all artifacts
for which a version conflict exists, and removes from the diagram
any artifacts that do not have version conflicts, or transitively depend
on artifacts with version conflicts.

The -H / --highlight option now supports multiple values.

Edges (the lines that represent dependencies) are now weighted;
highlighted (blue) edges are higher weight than normal,
and version conflict (red) edges are even higher weight. Higher weight
lines are generally straighter, with other nodes and edges moved out of
the way. This improves clarity of complex dependency charts.

In addition, highlight and version conflict edges are drawn slightly thicker,
as are highlighted nodes.

## 0.1.3 -- 24 Feb 2017

Group, module, and version each on their own line.
Added -H / --highlight option to highlight an artifact and dependencies to it, in blue.

## 0.1.2 -- 17 Feb 2017

Added the -s / --save-dot option.

vizdeps now attempts to show all linkages for each dependency:
if two dependencies A and B have a shared dependency C, then there
will be arrows from A to C and B to C.  Previously, there would be
a single arrow, from either A to C or from B to C.

Changed dependency versions are highlighted in red.
For example, if A depends on `[C "0.1.1"]` and B depends on
`[C "0.2.7"]` then the dependency from A to C will be marked in
red and labeled "0.1.1" (this assumes the B dependency took
precedence).

`lein deps :tree` reports much the same information with its
"possibly confusing dependencies found" message.

## 0.1.1 -- 29 Jul 2016

Create the output folder if it does not already exist.

## 0.1.0 -- 29 Jul 2016

Initial release.
