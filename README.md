# Log Lens

Log Lens is an IntelliJ IDEA 2025.3 plugin for Java projects using SLF4J and Logback. It adds two independent, source-preserving editor views:

- Message Lens replaces complete `{}` placeholders with their Java argument expressions while leaving the document, clipboard, whitespace, concatenation and saved source unchanged.
- Route Lens shows statically provable Logback file destinations, disabled calls, or a conservative unknown marker. Hover explains the logger/appender chain and clicking a destination opens its XML appender.

The status-bar `Log Lens` popup is the V0.1 control center. It binds one project Logback file, discovers standard project candidates, reports parser warnings, and updates the two lens toggles and colors immediately. Project bindings are workspace-local; display preferences are application-wide.

## Supported V0.1 boundary

- IntelliJ IDEA 2025.3.x, Java source, SLF4J classic `trace/debug/info/warn/error` calls.
- One active Logback XML per project.
- Logger/root level and additivity; File, RollingFile, Async and Console appenders; standard ThresholdFilter.
- Rolling appenders show `<file>` as the active file, or an unexpanded `fileNamePattern` marked as a pattern.
- Dynamic/unsupported configuration stays Raw or unknown. The parser never starts Logback, loads user classes, resolves external entities, or accesses network resources.

Kotlin, Log4j2, fluent SLF4J, Spring profiles, includes, conditional/sifting/custom routing, runtime validation, module mappings and Marketplace publishing are outside V0.1.
