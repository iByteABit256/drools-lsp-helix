# Drools LSP

An experimental [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) (LSP) implementation for the [Drools Rule Language](https://docs.jboss.org/drools/release/latest/drools-docs/html_single/#drl-rules-con_drl-rules) (DRL).

The project is split into a **server** (a set of Java/Maven modules that implement the LSP) and a **client** (a VSCode extension). The server is a standalone LSP server that speaks over stdio, so it can be driven by any LSP-capable editor — the bundled VSCode extension, Neovim, Helix, and so on.

## Features

The server provides:

- **Diagnostics** — DRL syntax errors, plus Java errors and warnings for the Java embedded in DRL (rule `then` bodies and `function` bodies), compiled with the Eclipse JDT compiler.
- **Completion** — context-sensitive DRL keywords and constructs (via the ANTLR4 C3 algorithm) plus semantic completion of symbols defined in the document.
- **Hover**
- **Go to definition**
- **Find references**
- **Incremental text synchronization**

## Server architecture

The server is a multi-module Maven project:

1. **drools-parser** — ANTLR4-based DRL parsing. The grammar is derived from upstream Drools. A single reusable parse result (`DRLParseResult`) carries the token stream, the compilation unit, the syntax errors, and a symbol index (`DRLSymbolIndex`) of the DRL constructs (rules, functions, globals, queries, templates, ...). This module has no dependency on the Java or LSP layers.
2. **drools-completion** — completion built on the ANTLR4 C3 (Context Sensitive Completion) algorithm (`com.vmware.antlr4-c3`), augmented with semantic completion from the parser's symbol index. Depends on `drools-parser`.
3. **drools-java** — runs the Eclipse JDT (ECJ) batch compiler over the Java embedded in DRL. `JavaIslandExtractor` locates the Java "islands" (rule `then` bodies and `function` bodies); `JdtJavaService` wraps each in a synthetic class/method that mirrors its real scope (a function keeps its return type and formal parameters, a rule `then` keeps the DRL `global` variables) and compiles it, mapping problems back to the original DRL offsets; `MavenClasspathResolver` builds the classpath from the nearest `pom.xml` (transitive, resolved against the local Maven repository, cached); `JdkClasspathProvider` supplies the running JDK's classes. Has no dependency on the LSP layer.
4. **drools-lsp-server** — the LSP gateway, built on `org.eclipse.lsp4j`. It wires the parser, completion, and Java diagnostics together and speaks LSP over stdio; it holds no business logic of its own. It implements incremental text sync (with save), publishes diagnostics on a debounced, version-guarded schedule, and serves completion / hover / go-to-definition / references from a shared per-document parse cache.

### Performance

The document service is tuned for interactive editing:

- Diagnostics are **debounced** (300 ms) and **version-guarded**, so a burst of keystrokes coalesces into a single validation pass and stale results are dropped.
- Validation runs on a dedicated single-thread scheduler and interactive requests on a separate pool, so a backlog of validation work can never starve completion / hover / definition / references.
- A **single parse per document version** is shared across diagnostics, completion, hover, definition, and references.
- The expensive JDT classpath name environment is **cached per classpath** and reused across keystrokes.

## Building the server

Prerequisites: **JDK 17** and **Maven**.

```bash
mvn clean package
```

This builds all modules and produces a self-contained (shaded) jar:

```
drools-lsp-server/target/drools-lsp-server-jar-with-dependencies.jar
```

The jar is a standalone stdio LSP server (Main-Class `org.drools.lsp.server.Main`):

```bash
java -jar drools-lsp-server-jar-with-dependencies.jar
```

`mvn clean install` additionally copies the jar into `client/lib/` so the VSCode extension can pick it up.

A Windows launcher, `drools-lsp.bat`, is provided at the repository root; it locates the jar next to the script (or in a `lib/` subdirectory) and starts it with `java -jar`.

## Using the server

The server communicates over stdio using the LSP, so any LSP-capable client can drive it. Below are setups for VSCode, Neovim, and Helix.

### VSCode

1. Download the latest `vscode-extension-drl-editor-<version>.vsix` from [releases](https://github.com/kiegroup/drools-lsp/releases).
2. In VSCode, open `Extensions` (Ctrl+Shift+X), then `...` (top-right corner) → `Install from VSIX...` and select the downloaded file.
3. Open a `.drl` file and start editing.

#### Developer notes

**Precompiled server — no debug**

1. Build the server: `mvn clean package`
2. `cd client`
3. `npm install`
4. `code .` to open the client in VSCode
5. Open `Run and Debug` (Ctrl+Shift+D) and start `Run Extension`
6. A new `Extension Development Host` window opens with the `drl` extension enabled
7. To surface a server-side event, add `server.getClient().showMessage(new MessageParams(MessageType.Info, "text"));` in the server code

**Connected remote server — debug**

1. Build the server: `mvn clean package`
2. Run `DroolsLspTCPLauncher` from the IDE in debug mode; the server listens on port `9925`
3. `cd client`
4. `npm install`
5. `code .`
6. Open `Run and Debug` (Ctrl+Shift+D) and start `Debug Extension`
7. The extension connects to the server on port `9925`
8. A new `Extension Development Host` window opens with the `drl` extension enabled
9. Set breakpoints in the server code to debug

### Neovim

[Neovim](https://neovim.io/) has [built-in](https://neovim.io/doc/user/lsp.html) LSP support, however client configuration is a manual process. It can be made a lot easier, though, if you leverage some of the many plugins available to do the hard parts for you, as you will see in the example below. _Please note:_

- Neovim will connect to the drools-lsp-server directly, bypassing the VSCode client extension.
- You are required to have a java runtime environment installed on your system, either with the `JAVA_HOME` environment variable set, or with the `java` command locatable in your `PATH`.
- The example below only shows the relevant portions of one's nvim `init.lua` file, assuming user familiarity with Neovim configuration (including working with plugins and [keymappings](https://github.com/neovim/nvim-lspconfig#Suggested-configuration), among other things).

**Example Configuration**

1. Use [packer.nvim](https://github.com/wbthomason/packer.nvim) (plugin installer) to install [nvim-lspconfig](http://github.com/neovim/nvim-lspconfig) (standard Neovim LSP configurations), [mason.nvim](https://github.com/williamboman/mason.nvim) (an _excellent_ LSP/DAP/Linter/Formatter package manager), and [mason-lspconfig.nvim](https://github.com/williamboman/mason-lspconfig.nvim) (the `mason.nvim` to `nvim-lspconfig` bridge):

```lua
  use 'wbthomason/packer.nvim'
  use {
    'neovim/nvim-lspconfig',
    requires = {
      'williamboman/mason.nvim',
      'williamboman/mason-lspconfig.nvim',
    },
  }
```

2. Get `mason` up-and-running:

```lua
require('mason').setup {}
require('mason-lspconfig').setup {}
```

3. Initialize the `drools-lsp` server (configuration options [here](https://github.com/neovim/nvim-lspconfig/blob/master/doc/server_configurations.md#drools_lsp)):

```lua
require('lspconfig').drools_lsp.setup {
  -- configuration options can be put here
  -- when using mason, nothing is required!
}
```

4. Add automatic filetype detection of DRL files (necessary to trigger the language server startup):

```lua
vim.cmd[[ autocmd BufRead,BufNewFile *.drl set filetype=drools ]]
```

5. Startup `nvim` anew, have `packer` install the plugins, have `mason` [install the language server](https://github.com/williamboman/mason.nvim/blob/main/PACKAGES.md#drools-lsp), and start editing DRL!

```vim
:PackerSync
:MasonInstall drools-lsp
:edit your.drl
```

### Helix

[Helix](https://helix-editor.com/) has built-in LSP support. Configure it in `languages.toml` (`~/.config/helix/languages.toml` on Linux/macOS, `%APPDATA%\helix\languages.toml` on Windows):

```toml
[language-server.drools-lsp]
command = "java"
args = ["-Xms256m", "-Xmx1024m", "-XX:+UseG1GC", "-jar", "/path/to/drools-lsp-server-jar-with-dependencies.jar"]

[[language]]
name = "drools"
scope = "source.drools"
file-types = ["drl"]
roots = ["pom.xml", ".git"]
grammar = "drools"
comment-tokens = "//"
language-servers = ["drools-lsp"]

[[grammar]]
name = "drools"
source = { git = "https://github.com/iByteABit256/tree-sitter-drools", rev = "f1404d2a3974dfcfd4193246e763edeeb4b399c1" }
```

Notes:

- `java` must be resolvable on your `PATH` (or set `command` to the full path to your `java` executable).
- The JVM flags (`-Xms` / `-Xmx` / `-XX:+UseG1GC`) are optional but recommended.
- The `[[grammar]]` block provides tree-sitter syntax highlighting and is optional; the LSP works without it.
