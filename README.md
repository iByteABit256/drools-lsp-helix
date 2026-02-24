# Helix adaptation of Drools LSP
This is a fork of [Drools LSP](https://github.com/kiegroup/drools-lsp) adapted for usage with the [Helix](https://helix-editor.com/) editor.

## Installation

### Binary Release

1. Download `drools-lsp.tar.gz` from the [latest release](https://github.com/iByteABit256/drools-lsp-helix/releases)
2. Extract it — you will find two files:
   - `drools-lsp` — the launcher script
   - `drools-lsp-server-jar-with-dependencies.jar` — the server
3. Place both files in the same directory on your `PATH`:

   **Unix:**
   ```sh
   mkdir -p ~/.local/bin
   tar -xzf drools-lsp.tar.gz -C ~/.local/bin
   chmod +x ~/.local/bin/drools-lsp
   ```
   Ensure `~/.local/bin` is on your `PATH`. Add this to your shell profile (`.bashrc`, `.zshrc`, etc.) if needed:
   ```sh
   export PATH="$HOME/.local/bin:$PATH"
   ```

   **Windows:**
   ```bat
   mkdir "%LOCALAPPDATA%\Programs\drools-lsp"
   tar -xzf drools-lsp.tar.gz -C "%LOCALAPPDATA%\Programs\drools-lsp"
   ```
   Then add `%LOCALAPPDATA%\Programs\drools-lsp` to your `PATH` via System Properties > Environment Variables.

4. Verify the installation:
   ```sh
   drools-lsp
   ```
   The server should start and wait for input. Press `Ctrl+C` to exit.

### Building from Source

1. Clone the repository and build with Maven:
   ```sh
   git clone https://github.com/iByteABit256/drools-lsp-helix.git
   cd drools-lsp-helix
   mvn clean package
   ```
2. Copy the launcher script and jar to a directory on your `PATH`:

   **Unix:**
   ```sh
   cp drools-lsp-server/scripts/drools-lsp ~/.local/bin/
   cp drools-lsp-server/target/drools-lsp-server-jar-with-dependencies.jar ~/.local/bin/
   chmod +x ~/.local/bin/drools-lsp
   ```

   **Windows:**
   ```bat
   mkdir "%LOCALAPPDATA%\Programs\drools-lsp"
   copy drools-lsp-server\scripts\drools-lsp "%LOCALAPPDATA%\Programs\drools-lsp"
   copy drools-lsp-server\target\drools-lsp-server-jar-with-dependencies.jar "%LOCALAPPDATA%\Programs\drools-lsp"
   ```

---

Overview
===
This is an experimental implementation of [LSP](https://microsoft.github.io/language-server-protocol/) based project intended to provide support for a [DRL](https://docs.jboss.org/drools/release/latest/drools-docs/html_single/#drl-rules-con_drl-rules) text editor.

It is composed of 2 parts: the **server** containing the actual LSP implementation and the **client** which is a VSCode extension consuming the services provided by the server.

The server is a plain Java/Maven project. Executing a `mvn clean package` in its folder will generate a jar file that will be automatically linked and consumed by the client when executed as a VSCode extension.

Server Architecture
===

The server part is composed of three modules:
1. **drools-parser** - responsible for actual drl-syntax parsing, eventually invoking a JAVA-LSP engine to read the `RHS` content (that is plain Java code); The antlr4 grammar files are taken from the latest drools. 
2. **drools-completion** - used to provide completion suggestion using the C3 engine; it depends on `com.vmware.antlr4-c3:antlr4-c3` and on `drools-parser`
3. **drools-lsp-server** - the "gateway" between the client and the parsing/completion logic; by itself it should not implement any business logic, but should be concerned only with communication; it depends directly on `drools-completion`

[VSCode](https://code.visualstudio.com/) Usage
===

1. Download the latest release of `vscode-extension-drl-editor-<version>.vsix` from [here](https://github.com/kiegroup/drools-lsp/releases)
2. Install the extension in VSCode by selecting `Extensions` (Ctrl+Shift+X) and then `...` (top-right corner) and `Install from VSIX...` to install the downloaded file
3. Open a `.drl` file and start editing

### Developer notes
**Precompiled-server - no debug**
1. package server side code with `mvn clean package`
2. goto `client` directory
3. issue `npm install`
4. issue `code .` to start VSCode in that directory
5. inside VSCode, select `Run and Debug` (Ctrl+Shift+D) and then start `Run Extension`
6. a new `Extension Development Host` window will appear, with `drl` extension enabled
7. to "debug" server-side event, add `server.getClient().showMessage(new MessageParams(MessageType.Info, {text}));` in server-side code

**Connected remote server - debug**
1. package server side code with `mvn clean package`
2. start server with `DroolsLspTCPLauncher` from IDE on debug mode; this will start the LSP-server listening on port `9925`
3. goto `client` directory
4. issue `npm install`
5. issue `code .` to start VSCode in that directory
6. inside VSCode, select `Run and Debug` (Ctrl+Shift+D) and then start `Debug Extension`
7. the extensions will establish a connection to the server running at port `9925`
8. a new `Extension Development Host` window will appear, with `drl` extension enabled
9. to "debug" server-side event, add breakpoints in server-side code

[Neovim](https://neovim.io/) Usage
===
Neovim has [built-in](https://neovim.io/doc/user/lsp.html) LSP support, however client configuration is a manual process. It can be made a lot easier, though, if you leverage some of the many plugins available to do the hard parts for you, as you will see in the example below. _Please note:_
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
