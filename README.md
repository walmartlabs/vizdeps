# visualize-dependencies

An alternative to `lein deps :tree` that uses Graphviz to present
a dependency diagram.

## Usage

Put `[visualize-dependencies "0.1.0"]` into the `:plugins` vector of your `:user`
profile.

```
Usage: lein vizdeps [options]

Options:
  -o, --output-file FILE  target/dependencies.pdf  Output file path. Extension chooses format: pdf or png.
  -n, --no-view                                    If given, the image will not be opened after creation.
  -d, --dev                                        Include :dev dependencies in the graph.
  -h, --help                                       This usage summary.
```
  
## License

Copyright © 2016 Walmartlabs

Distributed under the Apache Software License 2.0.
