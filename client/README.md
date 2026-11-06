# DRL (Drools Rule Language) Editor Extension

This VSCode extension provides syntax highlighting and a full set of language features for DRL files, backed by the [drools-lsp](https://github.com/kiegroup/drools-lsp) language server.

## Features

- Syntax highlighting (TextMate grammar) and language configuration
- Diagnostics — DRL syntax errors plus Java errors/warnings for the Java embedded in DRL
- Code completion (context-sensitive DRL keywords plus symbols defined in the document)
- Hover
- Go to definition
- Find references

The language features are provided by the `drools-lsp-server`, which the extension starts automatically from `lib/drools-lsp-server-jar-with-dependencies.jar`.

## How to build

Under `client` directory, run:

```bash
npm install
npm run pack:dev
```

vsix file will be generated in `dist` directory.

## Known Issues

- Code completion may suggest words that are not valid in the current context
- This is alpha version. If you find any issues, please report them in [github issues](https://github.com/kiegroup/drools-lsp/issues)
