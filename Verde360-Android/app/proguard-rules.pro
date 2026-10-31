# O app não usa reflexão nem @JavascriptInterface (a ponte com a página usa WebMessageListener),
# então as regras padrão do R8 bastam. Mantém nomes de arquivo e linhas para ler relatórios de falha.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
