[![Clojars Project](https://img.shields.io/clojars/v/com.paren/serene.svg)](https://clojars.org/com.paren/serene)

# Serene

Generate [clojure.spec](https://clojure.org/about/spec) with [GraphQL](https://graphql.org/) and extend GraphQL with clojure.spec.

* **100% GraphQL schema spec coverage**
* **Works with any GraphQL API**
* **Extend GraphQL with your own specs**
* **Works with Clojure & ClojureScript**

<!-- markdown-toc start - Don't edit this section. Run M-x markdown-toc-refresh-toc -->
**Table of Contents**

- [Serene](#serene)
    - [Usage](#usage)
        - [QuickStart](#quickstart)
        - [`def-specs`](#def-specs)
            - [`alias`](#alias)
            - [`extend`](#extend)
            - [`prefix`](#prefix)
    - [Spec Names](#spec-names)
        - [1st level](#1st-level)
        - [2nd level](#2nd-level)
        - [3rd level](#3rd-level)
    - [Rationale](#rationale)
    - [How It Works](#how-it-works)
    - [Status](#status)
    - [License](#license)

<!-- markdown-toc end -->

## Usage

### QuickStart

The heart of Serene, and perhaps the *only* function that you will need to use, is `paren.serene/def-specs`.
`def-specs` works with any GraphQL API.
Just query the API with the query defined at `paren.serene/introspection-query` and pass the response to `def-specs`.
The compilation happens during macro expansion and all arguments to `def-specs` are explicitly `eval`ed.
You can define specs for your entire GraphQL API in one line of code.

Let's say you have a project called "Serenity":

```clojure
(ns serenity.now
  (:require
   [clojure.spec.alpha :as s]
   [paren.serene :as serene]))

(defn execute-query
  "Takes a GraphQL query string, executes it against the schema, and returns the results."
  [query-string]
  ;; Implementation elided
  )

(serene/def-specs (execute-query serene/introspection-query))

(s/valid? ::User {:firstName "Frank" :lastName "Costanza"}) ;=> true
```

### `def-specs`

**Note**: All arguments to `def-specs` are `eval`ed.

`def-specs` optionally takes a transducer as the second argument.
This transducer will be called in the middle of the compilation and will receive map entries where the keys are qualified keyword spec names and the values are spec forms.
In this way, you can change any part of the output.
Serene ships with some default transducer-returning functions for common use cases.
Since these are just transducers, they can be combined with `comp`.

#### `alias`

`paren.serene/alias` is a function which receives a function (or map) of names to alias(es) and returns a transducer that aliases named specs.

The following would cause both `:api/query` and `:api.query/root` to be defined as aliases of the `:serenity.now/Query` type spec and would cause `:api.query/get-node` to be defined as an alias of the `:serenity.now.Query/node` field spec:

```clojure
(serene/def-specs (execute-query serene/introspection-query)
  (serene/alias {:serenity.now/Query #{:api/query :api.query/root}
                 :serenity.now.Query/node :api.query/get-node}))
 ```

#### `extend`

`paren.serene/extend` is a function which receives a function (or map) of spec names to spec forms and returns a transducer that will combine them with `s/and`.
For example, if you have a custom `Keyword` scalar you could use the following to add custom scalar validation:

```clojure
(serene/def-specs (execute-query serene/introspection-query)
  (serene/extend {:serenity.now/Keyword `keyword?}))
```

#### `prefix`
`paren.serene/prefix` is a function which receives a function (or map) of spec names to spec prefixes and returns a transducer that will rename all specs prefixed with `*ns*`.
For example, instead of having a long namespace prefix, you might want to prefix your specs with `:gql`:

```clojure
(serene/def-specs (execute-query serene/introspection-query)
  (serene/prefix (constantly :gql)))
  ```

This will produce specs like `:gql/Query`, `:gql.Query/node`, etc.

#### `postfix-args`
`paren.serene/postfix-args` is a function which receives a function (or map) of field args spec names to postfixes and returns a transducer that will rename all specs postfixed with `%`.
For background, GraphQL field arguments are individually but not named as a whole.
As such, we define a `s/keys` spec for every field named `(str field-name "%")`.
`postfix-args` is used to rename `%` to a postfix of your choice.
For example, if you want arguments specs to end with `-args`, you would do this:

```clojure
(serene/def-specs (execute-query serene/introspection-query)
  (serene/postfix-args (constantly :-args)))
  ```

## Spec Names

Spec names are keywords that are namespaced by their position in the schema. For the examples below, let's assume a prefix of `:gql` instead of `*ns*`.

### 1st level

Type definitions (enum, input object, interface, object, scalar, and union) are defined at this level.

Examples: `:gql/String`, `:gql/User`, `:gql/Mutation`, `:gql/CreateUserInput`

### 2nd level

Enum values, interface fields, input object fields, and object fields are defined at this level.

Examples: `:gql.User/email`, `:gql.Mutation/createUser`, `:gql.Status/COMPLETE`

Additionally, special anonymous `s/keys` specs for field arguments are defined at this level.

Example: `:gql.Mutation/createUser%`

### 3rd level

Input values (argument fields) are defined at this level.

Examples: `:gql.Mutation.createUser/email`, `:gql.Mutation.createUser/firstName`

## Rationale

It is our experience that GraphQL is superior to REST for most APIs used for web and mobile applications. We also think that clojure.spec provides a good balance of expressiveness and strictness.

GraphQL's type system provides a point of leverage for API providers and consumers.
Because GraphQL schemas are introspectable, [GraphQL tooling](https://github.com/chentsulin/awesome-graphql#tools) tends to be very powerful.
Some languages, like OCaml/Reason, can even [validate queries and response code at compile time](https://github.com/mhallin/graphql_ppx).

If statically typed languages can leverage GraphQL to this extent, Clojure should be able to do even more. Serene aims to address this.

## How It Works

Serene works in much the same way as [GraphiQL](https://github.com/graphql/graphiql) and other GraphQL tools; it uses GraphQL's introspection capabilities.
GraphQL schemas are introspectable, meaning that you can query a running API to determine all of the capabilities of that API.

Serene uses [this introspection query](https://github.com/paren-com/serene/blob/master/resources/main/paren/serene/IntrospectionQuery.graphql), which is conveniently defined as `paren.serene/introspection-query`, to generate specs that match your API.

## Status

If clojure.spec is alpha, then Serene is extra alpha.

Consider everything to be an implementation detail unless it is explicitly documented here.

Serene uses [@ptaoussanis](https://github.com/ptaoussanis)' [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md).

## License

Copyright © Paren, LLC

Distributed under the [Eclipse Public License version 2.0](http://www.eclipse.org/legal/epl-v20.html).
