# 启动「星球演化」后端（离线，纯本地 .m2 依赖）
#
# 说明：本机 .m2 中 spring-boot-loader-tools:3.5.8 只有 .pom、没有 .jar，
# 因此 spring-boot-maven-plugin 无法离线 repackage，没有可执行 fat jar。
# 这里改为按 Spring Boot 3.5.8 实际解析到的版本拼 classpath 运行。
#
# 启动后浏览器打开 http://127.0.0.1:8080/ 即为前端页面（静态文件在
# src/main/resources/static，需先执行 mvn -o clean package 才会更新到 target/classes）。

$ErrorActionPreference = 'Stop'

$repo = Join-Path $env:USERPROFILE '.m2\repository'
$classes = Join-Path $PSScriptRoot 'target\classes'

# 版本取自 .m2 中 spring-boot-dependencies-3.5.8.pom 的 dependencyManagement
$artifacts = @(
    'org\springframework\boot\spring-boot\3.5.8',
    'org\springframework\boot\spring-boot-autoconfigure\3.5.8',
    'org\springframework\boot\spring-boot-starter\3.5.8',
    'org\springframework\boot\spring-boot-starter-web\3.5.8',
    'org\springframework\boot\spring-boot-starter-json\3.5.8',
    'org\springframework\boot\spring-boot-starter-tomcat\3.5.8',
    'org\springframework\boot\spring-boot-starter-logging\3.5.8',
    'org\springframework\spring-core\6.2.14',
    'org\springframework\spring-beans\6.2.14',
    'org\springframework\spring-context\6.2.14',
    'org\springframework\spring-aop\6.2.14',
    'org\springframework\spring-expression\6.2.14',
    'org\springframework\spring-web\6.2.14',
    'org\springframework\spring-webmvc\6.2.14',
    'org\springframework\spring-jcl\6.2.14',
    'org\apache\tomcat\embed\tomcat-embed-core\10.1.49',
    'org\apache\tomcat\embed\tomcat-embed-el\10.1.49',
    'org\apache\tomcat\embed\tomcat-embed-websocket\10.1.49',
    'com\fasterxml\jackson\core\jackson-databind\2.19.4',
    'com\fasterxml\jackson\core\jackson-core\2.19.4',
    'com\fasterxml\jackson\core\jackson-annotations\2.19.4',
    'com\fasterxml\jackson\datatype\jackson-datatype-jsr310\2.19.4',
    'com\fasterxml\jackson\module\jackson-module-parameter-names\2.19.4',
    'org\slf4j\slf4j-api\2.0.17',
    'ch\qos\logback\logback-classic\1.5.21',
    'ch\qos\logback\logback-core\1.5.21',
    'org\apache\logging\log4j\log4j-to-slf4j\2.24.3',
    'org\apache\logging\log4j\log4j-api\2.24.3',
    'org\slf4j\jul-to-slf4j\2.0.17',
    'jakarta\annotation\jakarta.annotation-api\2.1.1',
    'org\yaml\snakeyaml\2.4',
    'io\micrometer\micrometer-observation\1.15.6',
    'io\micrometer\micrometer-commons\1.15.6'
)

if (-not (Test-Path $classes)) {
    throw "未找到 $classes，请先执行 mvn -o clean package"
}

$paths = New-Object System.Collections.Generic.List[string]
$paths.Add($classes)

$missing = @()
foreach ($a in $artifacts) {
    $dir = Join-Path $repo $a
    $jar = Get-ChildItem -LiteralPath $dir -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
        Select-Object -First 1
    if ($jar) { $paths.Add($jar.FullName) } else { $missing += $a }
}

if ($missing.Count -gt 0) {
    Write-Host '以下运行时依赖在本地仓库中缺失：'
    $missing | ForEach-Object { Write-Host "  $_" }
    throw '缺少依赖，已停止（不联网下载）。'
}

Write-Host ("classpath 条目数：{0}" -f $paths.Count)
& java -cp ($paths -join ';') com.planet.evolution.PlanetEvolutionApplication
exit $LASTEXITCODE
