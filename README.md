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
    - [QuickStart](#quickstart)
    - [Rationale](#rationale)
    - [Usage](#usage)
        - [Generated Spec Names](#generated-spec-names)
        - [Generating Specs](#generating-specs)
            - [`paren.serene/def-specs`](#parenserenedef-specs)
            - [`paren.serene/spit-specs`](#parenserenespit-specs)
        - [Getting your GraphQL Schema](#getting-your-graphql-schema)
            - [`paren.serene.schema/fetch`](#parensereneschemafetch)
            - [`paren.serene.schema/query`](#parensereneschemaquery)
        - [Compilation Options](#compilation-options)
            - [`:extend`](#extend)
            - [`:alias`](#alias)
            - [`:prefix`](#prefix)
    - [How It Works](#how-it-works)
    - [Status](#status)
    - [License](#license)

<!-- markdown-toc end -->

## QuickStart

Serene can generate specs for an entire GraphQL API in one line of code.

Let's say you have a project called "Serenity" and a GraphQL API available at `"http://localhost:3000/graphql"`:

```clojure
(ns serenity.now
  (:require
   [clojure.spec.alpha :as s]
   [paren.serene :as serene]
   [paren.serene.schema :as schema]))

;; Define specs
(serene/def-specs (schema/fetch "http://localhost:3000/graphql"))

;; Use specs
(s/valid? ::User {:firstName "Frank" :lastName "Costanza"}) ;=> true
```

## Rationale

It is our experience that GraphQL is superior to REST for most APIs used for web and mobile applications. We also think that clojure.spec provides a good balance of expressiveness and strictness.

GraphQL's type system provides a point of leverage for API providers and consumers.
Because GraphQL schemas are introspectable, [GraphQL tooling](https://github.com/chentsulin/awesome-graphql#tools) tends to be very powerful.
Some languages, like OCaml/Reason, can even [validate queries and response code at compile time](https://github.com/mhallin/graphql_ppx).

If other languages can leverage GraphQL to this extent, Clojure should be able to as well.
Serene aims to address this.

## Usage

### Generated Spec Names

Spec names are keywords that are prefixed and namespaced by their position in the schema.
For the example below, let's assume a prefix of `:gql`, though the prefix is customizable.

```graphql
# Built-in scalars are defined: :gql/Boolean, :gql/Float , :gql/ID , :gql/Int, :gql/String

scalar Email # :gql/Email

enum Mood { # :gql/Mood
  SERENE # :gql.Mood/SERENE
  ANNOYED # :gql.Mood/ANNOYED
  ANGRY # :gql.Mood/ANGRY
}

type User { # :gql/User
  id: ID! # :gql.User/id
  username: String! # :gql.User/username
  email: Email! # :gql.User/email
  mood: Mood # :gql.User/mood
}

type Mutation { # :gql/Mutation
  createUser(
    username: String!, # :gql.Mutation.createUser/username
    email: Email! # :gql.Mutation.createUser/email
    mood: Mood # :gql.Mutation.createUser/mood
    # :gql.Mutation.createUser/&args is an anonymous `s/keys` spec for args map
  ): User! # :gql.Mutation/createUser
}
```

### Generating Specs

#### `paren.serene/def-specs`

**Note**: All arguments to `def-specs` are `eval`ed.

`def-specs` is a macro that will define specs for a GraphQL schema.
It takes compilation options as an optional second argument.
[Compilation options](#compilation-options) are documented below.

```clojure
(serene/def-specs gql-schema options)
```

#### `paren.serene/spit-specs`

`spit-specs` is like `def-specs`, but outputs `s/def` forms to a file.
The file path and namespace are the first two arguments to `spit-specs`.

```clojure
(serene/spit-specs "src/api/specs.cljc" 'api.specs gql-schema options)
```

### Getting your GraphQL Schema

#### `paren.serene.schema/fetch`

`fetch` takes a GraphQL server endpoint and optional configuration.

```clojure
(schema/fetch "https://api.github.com/graphql" {:headers {"Authorization" (str "bearer " gh-access-token)}})
```

#### `paren.serene.schema/query`

`query` is [this GraphQL introspection query string](https://github.com/paren-com/serene/blob/master/resources/main/paren/serene/IntrospectionQuery.graphql).

`fetch` works by asking the HTTP GraphQL server to execute `query`.

You can use `query` directly if your GraphQL API is not accessible via HTTP.

### Compilation Options

#### `:extend`

`:extend` is a function or map of spec names to spec forms. If a spec form is returned, it will be combined with default specs using `s/and`.

For example, if you have a custom `Keyword` scalar you could use the following to add custom scalar validation:

```clojure
(serene/def-specs gql-schema {:extend {:Keyword `keyword?}})
```

#### `:alias`

`:alias` is a function or map which receives unprefixed spec names and returns aliases for those names.

```clojure
(serene/def-specs gql-schema {:alias {:Query #{:api/query :api.query/root}
                                      :Query/node :api.query/get-node}})
```

This would cause both `:api/query` and `:api.query/root` to be defined as aliases of the `:Query` type spec and would cause `:api.query/get-node` to be defined as an alias of the `:Query/node` field spec.

#### `:prefix`

`:prefix` is a wrapper around `:alias` for the common case of altering default `*ns*` prefixes.

For example, instead of having a long namespace prefix, you might want to prefix your specs with `:gql`:

```clojure
(serene/def-specs gql-schema {:prefix :gql})
  ```

This will produce specs like `:gql/Query`, `:gql.Query/node`, etc.

#### `:gen-object-fields`

`:gen-object-fields` will cause test.check generators for object types to generate all fields, 
even though all object fields are optional.

This is necessary if you are using test.check to generate data for object, interface, or union types
because all object fields are optional (clients can query for any combination of fields).

However, generating data for map specs where all keys are optional can be frustrating because you often end up with empty or nearly empty maps.
It is also not possible to always generate all fields, because objects can be cyclic, so you have to stop after some pre-determined level.

`:gen-object-fields` solves this by always generating all fields up to `n` where `n` is a configurable depth that defaults to `s/*recursion-limit*`.

```clojure
;; modify all objects to generate `s/*recursion-limit*` levels deep
(schema/def-specs gql-schema {:gen-object-fields true})

;; modify only `Query` to generate 5 levels deep
(schema/def-specs gql-schema {:gen-object-fields {:Query 5}})
```

#### Custom Compilation Options

All compilation options are implemented and documented in [`paren.serene.compiler.transducers`](https://github.com/paren-com/serene/blob/master/sources/main/paren/serene/compiler/transducers.cljc).

Custom compilation options can be added in the same way that default options are provided.

## How It Works

Serene works in much the same way as [GraphiQL](https://github.com/graphql/graphiql) and other GraphQL tools; it uses GraphQL's introspection capabilities.
GraphQL schemas are introspectable, meaning that you can query a running API to determine all of the capabilities of that API.

Serene uses [this introspection query](https://github.com/paren-com/serene/blob/master/resources/main/paren/serene/IntrospectionQuery.graphql), which is conveniently defined as `paren.serene/introspection-query`, to generate specs that match your API.

## Status

If clojure.spec is alpha, then Serene is extra alpha.

Consider everything to be an implementation detail unless it is explicitly documented here.

Serene uses [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md).

## License

Copyright © Paren, LLC

Distributed under the [Eclipse Public License version 2.0](http://www.eclipse.org/legal/epl-v20.html).
