# MTClone — Third-Party Notices

## jadx (vendored, modified)

`src/jadx/jadx/core/xmlgen/` contains code derived from
[jadx](https://github.com/skylot/jadx) — a dex to java decompiler.

Upstream project: https://github.com/skylot/jadx
License: Apache License 2.0
Copyright (c) 2014 Skylot

Files derived from upstream (with modifications, marked "MTClone mod"):
- `ParserConstants.java`      (verbatim)
- `ParserStream.java`         (verbatim, jetbrains annotations removed)
- `BinaryXMLStrings.java`   (negative-index guard added for obfuscated arsc)
- `CommonBinaryParser.java`   (verbatim)
- `ResourceStorage.java`      (IJadxSecurity dependency removed,
                               method references replaced with anonymous classes)
- `ResTableBinaryParser.java` (RootNode/deobfuscation integration removed,
                               raw span recording added for write-back,
                               decodeFiles/XML generation removed, Java 8 syntax)
- `entry/ResourceEntry.java`, `entry/RawValue.java`, `entry/RawNamedValue.java`,
  `entry/EntryConfig.java`, `entry/ProtoValue.java`  (verbatim)

The modifications add the "modification" layer (arsc write-back) on top of
jadx's proven binary parser, as requested.

## slf4j shim

`src/org/slf4j/Logger.java` and `src/org/slf4j/LoggerFactory.java` are minimal
shims written for MTClone (not from jadx) so the vendored parser code compiles
without the real slf4j dependency. They route log calls to `android.util.Log`
on device and stderr on desktop.
