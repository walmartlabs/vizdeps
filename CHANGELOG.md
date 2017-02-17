## 0.1.2 -- UNRELEASED

Added the -s / --save-dot option.

vizdeps now attempts to show all linkages for each dependency:
if two dependencies A and B have a shared dependency C, then there
will be arrows from A to C and B to C.  Previously, there would be
a single arrow, from either A to C or from B to C.

## 0.1.1 -- 29 Jul 2016

Create the output folder if it does not already exist.

## 0.1.0 -- 29 Jul 2016

Initial release.
