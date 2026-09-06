PocketPC Windows Setup

1. Extraia o ZIP.
2. Conecte o celular com Depuracao USB ativada.
3. Use Transferencia de arquivos / Android Auto como modo USB.
4. Execute INSTALL.bat.
5. Quando o Android SDK pedir licencas, leia e digite S se concordar.
6. Se o celular pedir a chave RSA da Depuracao USB, autorize.

O instalador:
- localiza ou clona Rogeriomatador/PocketPC;
- baixa Eclipse Temurin JDK 17 via API oficial Adoptium;
- valida o SHA-256 retornado pela API Adoptium;
- baixa Android Command-line Tools diretamente do Google;
- valida o SHA-256 oficial do pacote Android CLI;
- instala somente os componentes pinados em toolchains/android-build-lock.json;
- configura JAVA_HOME, ANDROID_SDK_ROOT e ANDROID_HOME no usuario;
- instala platform-tools/ADB;
- executa o PocketPC Doctor no final.

Nao instala Android Studio.
Nao embute JDK ou Android SDK dentro deste ZIP.
