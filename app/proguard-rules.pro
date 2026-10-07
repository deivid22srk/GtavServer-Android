# Regras R8/ProGuard do app.
# O core é usado via Kotlin puro; manter classes do motor HTTP (acessadas por reflexão? não —
# nada de reflexão no projeto; regras apenas de segurança).

# Compose já tem consumer rules. Manter linhas de erro legíveis em release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
