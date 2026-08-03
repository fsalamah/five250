# Conservative obfuscation only: rename com.acabes.five250.* identifiers so the jar isn't
# trivially readable/decompilable back to meaningful names, WITHOUT shrinking or optimizing -
# both of those passes are the ones known to break reflection-heavy code (GraalVM Truffle, the
# tn5250j terminal library), and this app leans on exactly that. Renaming alone is much lower
# risk: every internal call site gets updated consistently by ProGuard itself, and every class
# outside our own package (all of GraalVM/Truffle, tn5250j, and the JDK) is explicitly kept
# completely untouched below - not touched even for renaming - so their own reflection/dynamic
# class generation never encounters an unexpected name.
-dontshrink
-dontoptimize
-dontwarn **

# The JVM launches this jar via its manifest's literal "Main-Class: com.acabes.five250.Cli" -
# that class name and its main() signature MUST survive exactly as-is, or the jar simply won't
# start. (Every other com.acabes.five250.* class/method/field IS free to be renamed - internal
# call sites are all in this same analyzed program, so ProGuard keeps them consistent.)
-keep public class com.acabes.five250.Cli {
    public static void main(java.lang.String[]);
}

# Everything NOT in our own package - GraalVM/Truffle, tn5250j, and every JDK class - keeps its
# exact original names, completely unrenamed. Only com.acabes.five250.* is fair game above.
-keep class !com.acabes.five250.** {
    *;
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
