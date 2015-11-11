# clj-jruby-rack

A Clojure library designed that implements Ruby's [Rack](http://www.rubydoc.info/github/rack/rack/master/file/SPEC) as a Clojure [Ring](https://github.com/ring-clojure/ring/blob/master/SPEC) handler function.

## Usage

Generic Rack application or middleware:

```clj
(require 'ring.rack)
(ring.rack/wrap-rack-handler rack-function) ;<- FIXME
```

Rails app or middleware:

```clj
(require 'ring.rack)
(ring.rack/rails-app "path/to/rails/app") ;<- FIXME
```



## License

Copyright © 2015 FIXME
