[![Clojars Project](https://img.shields.io/clojars/v/com.paren/serene.svg)](https://clojars.org/com.paren/serene)

# Serene

Generate clojure.spec with GraphQL and extend GraphQL with clojure.spec.

* **100% spec coverage** Given _any_ GraphQL schema, generate specs for _every_:
  * enum & enum value
  * field 
  * input object
  * input value (argument)
  * interface
  * object
  * scalar
  * union
* **Extend with your own specs**
* **Works with Clojure & ClojureScript**

<!-- markdown-toc start - Don't edit this section. Run M-x markdown-toc-refresh-toc -->
**Table of Contents**

- [Serene](#serene)
    - [Background](#background)
        - [GraphQL & clojure.spec](#graphql--clojurespec)
        - [Rationale](#rationale)
        - [How It Works](#how-it-works)
    - [Usage](#usage)
        - [QuickStart](#quickstart)
        - [`def-specs` Options](#def-specs-options)
            - [`:alias`](#alias)
            - [`:extend`](#extend)
            - [`:prefix`](#prefix)
        - [Spec Names](#spec-names)
            - [1st level](#1st-level)
            - [2nd level](#2nd-level)
            - [3rd level](#3rd-level)
    - [Status](#status)
    - [License](#license)

<!-- markdown-toc end -->

## Background
The purpose of Serene is to utilize clojure.spec to produce more robust GraphQL APIs and to leverage GraphQL to create more robust applications. 

### GraphQL & clojure.spec

This documentation assumes knowledge of [GraphQL](https://graphql.org/) and [clojure.spec](https://clojure.org/about/spec).

If you don't know what GraphQL is, go watch [this talk](https://www.youtube.com/watch?v=sFUd-CtnJv8) and come back. We'll wait.



### Rationale

It is our experience that GraphQL is superior to REST for most for APIs used for web and mobile applications. We also think that clojure.spec provides a good balance of expressiveness and strictness. 

GraphQL's type system provides a point of leverage for API providers and consumers. 
Because GraphQL schemas are introspectable, [GraphQL tooling](https://github.com/chentsulin/awesome-graphql#tools) tends to be very powerful. 
Some languages, like [OCaml/Reason](https://github.com/mhallin/graphql_ppx), can even validate queries and response code at compile time.

If statically typed languages can utilize GraphQL to this extent, Clojure should be able to do even more dynamically. Serene aims to address this.

### How It Works

Serene works in much the same way as [GraphiQL](https://github.com/graphql/graphiql) and other GraphQL tools; it uses GraphQL's introspection features. 
GraphQL schemas are introspectable, meaning that you can query a running API to determine all of the capabilities of that API.

Serene uses [this introspection query](https://github.com/paren-com/serene/blob/master/resources/main/paren/serene/IntrospectionQuery.graphql), which is conveniently defined as `paren.serene/introspection-query`, to generate specs that match your API.

## Usage


### QuickStart

The heart of Serene, and the *only* function that you need to use, is `paren.serene/def-specs`. 
`paren.serene/def-specs` works with *any* GraphQL API. 
You just query the API with the query defined at `paren.serene/introspection-query` and pass the results to `paren.serene/def-specs`.
The compilation happens during macro expansion and all arguments to `def-specs` are explicitely `eval`ed. 
You can define specs for your entire GraphQL API in one line of code.

Let's say you have a project called "SerenityNow":

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

### `def-specs` Options

`def-specs` accepts the following options:

**Note**: All arguments to `def-specs` are `eval`ed.

#### `:alias`

`:alias` is a function (or map) of raw spec name keyword to alias(es). 
The following map would cause both `:api/query` and `:api.query/root` to be defined as aliases of the `:Query` type spec and would cause `:api.query/get-node` to be defined as an alias of the `:Query/node` field spec:

```clojure
{:Query #{:api/query :api.query/root}
 :Query/node :api.query/get-node}
 ```

#### `:extend`

`:extend` is a function (or map) of raw spec name keyword to spec. The spec with be `s/and`ed together with the basic spec for that spec name.
For example, if you have a custom `Keyword` scalar you could use the following `:extend` map too add custom scalar validation:

```clojure
{:Keyword `keyword?}
```

#### `:prefix`
`:prefix` is a keyword or symbol that should prefix all defined specs. It defaults to `*ns*`. If you were to call `def-specs` in the `serenity.now` namespace without defining a `:prefix` you would end of with specs like `:serenity.now/Query` and `:serenity.now.Query/node`. If you were to pass `:gql` as the prefix `:prefix` you would end up with specs like `:gql/Query` and `:gql.Query/node`.

### Spec Names

Spec names are keywords that are namespaced by their position in the schema. For the examples below, let's assume a `:prefix` of `:gql`.

#### 1st level

Type definitions (enum, input object, interface, object, scalar, and union) are defined at this level.
Examples: `:gql/String`, `:gql/User`, `:gql/Mutation`, `:gql/CreateUserInput`

#### 2nd level

Enum values, interface fields, input object fields, and object fields are defined at this level. 

Examples: `:gql.User/email`, `:gql.Mutation/createUser`, `:gql.Status/COMPLETE`

#### 3rd level

Input values (argument fields) are defined at this level.

Examples: `:gql.Mutation.createUser/email`, `:gql.Mutation.createUser/firstName`

Additionally, a special anonymous spec that is a `s/keys` spec for the arguments map is defined at this level.

Example: `:gql.Mutation.createUser/%`

## Status

If clojure.spec is alpha, then Serene is extra alpha.

Consider everything to be an implementation detail unless it is explicitely documented here.

Serene uses [@ptaoussanis](https://github.com/ptaoussanis)' [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md).

## License

Copyright © Paren, LLC

Distributed under the [Eclipse Public License version 2.0](http://www.eclipse.org/legal/epl-v20.html).
