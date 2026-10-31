# VERDE360 Ambiental · app Android

Aplicativo Android da plataforma VERDE360 (https://www.empreenderthz.com.br/Ambiental/).
Pacote `br.com.empreenderthz.verde360`, versão 1.2.0 (versionCode 3), mínimo Android 8.0 (API 26), alvo Android 16 (API 36).

## O que mudou em relação ao app-debug 1.1.0

**Pronto para a loja**
- Build de release com R8 (código e recursos reduzidos), sem `debuggable`, assinado pela chave de upload e gerado como `.aab`.
- `targetSdk 36`, exigido pela Google Play para novos apps e atualizações a partir de 31/08/2026.
- Variante de debug com sufixo `.debug`, para instalar ao lado da versão da loja.

**Correções para Android 15 e 16**
- Desenho de borda a borda com recuos do sistema: a barra superior não fica mais sob a barra de status e a barra inferior respeita a navegação por gestos e o teclado (o app 1.1.0 usava `setStatusBarColor`, que deixou de valer).
- Botão/gesto Voltar pelo `OnBackPressedDispatcher`, compatível com o voltar preditivo (no Android 16 o antigo `onBackPressed` não é mais chamado).
- Tela de abertura (SplashScreen) e ícone adaptativo com versão monocromática (ícones temáticos do Android 13+).

**Funcionalidades que não funcionavam no app**
- Campos de envio de arquivo (`input type="file"`, ex.: importação de GeoJSON) abrem o seletor do sistema.
- Exportações da página (relato e análise em JSON, gerados como `blob:`) agora são salvas em Downloads, com opção de compartilhar. Downloads comuns usam o gerenciador de downloads.
- Links externos abrem em Custom Tabs com as cores do app; `tel:` abre o discador; links `intent:` são tratados com segurança.
- Tela de falta de conexão com reconexão automática quando a rede volta; tela própria para erro 5xx do servidor.
- Links do VERDE360 abertos fora do app (WhatsApp, e-mail) podem abrir direto no app (App Links, ver `assetlinks.json`).

**Navegação nativa**
Barra inferior com Início, Voltar, Atualizar, Compartilhar e Mais (Denunciar ao Ibama, Fala.BR, ligar para a Linha Verde, abrir no navegador, política de privacidade e Sobre, com o aviso de não afiliação a órgãos públicos).

**Segurança**
Somente HTTPS (`network_security_config`), sem acesso a arquivos locais pelo WebView, sem conteúdo misto, Safe Browsing ativo, sem cookies de terceiros, sem backup de dados, depuração do WebView só na variante debug. A ponte com a página usa `WebMessageListener` restrito às origens `empreenderthz.com.br`, em vez de `addJavascriptInterface`. Permissões: apenas Internet e estado da rede.

## Como gerar o pacote para a Play Store

Requisitos: Android Studio recente (Narwhal 2025.1.3 ou mais novo) com o SDK 36 instalado.

1. **Abra a pasta** `Verde360-Android` no Android Studio e aguarde o Gradle sincronizar. Se o Studio sugerir atualizar o Android Gradle Plugin ou as bibliotecas, pode aceitar.
2. **Crie a chave de upload** (uma única vez; guarde o arquivo e as senhas em local seguro):
   ```
   keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   ou pelo menu *Build › Generate Signed App Bundle › Create new…*.
3. **Copie** `keystore.properties.example` para `keystore.properties` e preencha caminho, senhas e alias.
4. **Gere o bundle:**
   ```
   ./gradlew bundleRelease
   ```
   Resultado: `app/build/outputs/bundle/release/app-release.aab`.
5. **Teste antes de enviar** (aparelho com Android 15 ou 16 de preferência):
   ```
   ./gradlew installDebug
   ```
   Confira: barra superior abaixo da barra de status; voltar pelo gesto; relato exportado aparece em Downloads; importar GeoJSON abre o seletor; modo avião mostra a tela de conexão e recarrega sozinho ao religar; links externos abrem em aba própria.

A cada nova versão enviada, aumente `versionCode` (e `versionName`) em `app/build.gradle.kts`.

Endereços da plataforma e da política de privacidade ficam em `app/build.gradle.kts` (`HOME_URL`, `PRIVACY_URL`).

## Estrutura

```
app/src/main/java/br/com/empreenderthz/verde360/
  MainActivity.kt   WebView, navegação, links, erros, downloads, seletor de arquivos
  FileSaver.kt      gravação dos arquivos exportados (MediaStore / FileProvider)
app/src/main/res/
  layout/activity_main.xml, values/ (cores, textos, temas), drawable/ (ícones), xml/ (segurança, backup, FileProvider)
```

Ficha da loja, ícone, gráfico de destaque, política de privacidade e `assetlinks.json` estão na pasta `PlayStore`, ao lado deste projeto.
