# PRoot ARM64: compilação nativa experimental

Esta revisão produziu PRoot, loader, libtalloc e libandroid-shmem reais para Android ARM64/API 26 com NDK 29.0.14206865. Uma segunda compilação em diretório limpo terminou com sucesso usando a receita versionada. Os hashes e a auditoria ELF estão em `validation/proot-ndk-build.json`.

## Reproduzir

Em Linux x86_64, com Python 3.12+, make, patch, awk e o NDK fixado instalado:

```sh
python3 scripts/build-proot-ndk.py \
  --ndk /caminho/android-sdk/ndk/29.0.14206865 \
  --work /caminho/novo-diretorio-fora-do-repositorio \
  --source-cache /caminho/cache-de-fontes
```

O diretório de trabalho deve ser novo. O script verifica SHA-256 de fontes, receitas originais, patches e respostas de configuração cruzada. As respostas são premissas de compilação; não são testes executados no Android. Logs, comandos, códigos de saída, auditoria e binários ficam no diretório de trabalho. O workflow `PRoot NDK Build` automatiza o mesmo processo; sua execução no GitHub não foi validada nesta sessão.

## Alterações necessárias

- libtalloc usa SONAME sem versão, adequado ao empacotamento de bibliotecas Android.
- libandroid-shmem usa o diretório privado absoluto `PROOT_TMP_DIR`, com limite de tamanho e tratamento de erro. O app o deriva do bind de sistema gravável `/tmp`; o `TMPDIR` do guest continua `/tmp`.
- PRoot recebe o header C ausente; o gerador de offsets usa awk POSIX e exige símbolos válidos. O offset emitido foi comparado aos símbolos ELF.
- Os quatro artefatos usam segmentos LOAD alinhados a 16 KiB. Isso é uma verificação estrutural, não prova de funcionamento em aparelho com páginas de 16 KiB.

## Validação e limites

152 testes JVM e 18 verificadores Python passaram. Lint: 0 erros, 69 avisos, 1 sugestão. `:app:assembleDebug` também terminou com sucesso, incluindo CMake para arm64-v8a e x86_64, sem `skipNativeBuild`. Esse APK contém a camada nativa atual do PocketPC; ainda não inclui os novos binários PRoot.

A auditoria ELF não encontrou falhas estruturais nem bloqueios de empacotamento conhecidos. Dependências e avisos upstream ainda exigem revisão. Os textos de licenças das fontes foram registrados em `third_party/proot/SOURCE_LICENSE_EVIDENCE.json`; esse registro não substitui a aprovação de distribuição.

PRoot e loader não foram executados no Android. Integração de rootfs, Box64, Wine e gráficos permanece pendente. Roblox não foi executado e sua compatibilidade não está demonstrada. Nenhuma aprovação de runtime foi alterada. O pacote de build é experimental e não é instalador nem runtime pronto para o usuário.
