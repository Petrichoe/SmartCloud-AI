@echo off
setlocal EnableExtensions EnableDelayedExpansion
goto :main

:check_service
if not exist "%ROOT%%~2\pom.xml" (
    echo Missing Maven project for %~1: "%ROOT%%~2"
    set "INVALID=1"
)
if not exist "%ROOT%%~2\target\classes\%~3" (
    echo Missing compiled main class for %~1: "%ROOT%%~2\target\classes\%~3"
    set "INVALID=1"
)
for %%M in (%~4) do (
    if not exist "%ROOT%%%M\target\classes" (
        echo Missing compiled module output: "%ROOT%%%M\target\classes"
        set "INVALID=1"
    )
)
exit /b 0

:prepare_service
set "CP_FILE=%ROOT%%~2\target\codex-classpath.txt"
set "NEED_CP=0"
if not exist "!CP_FILE!" set "NEED_CP=1"
if exist "!CP_FILE!" for %%F in ("!CP_FILE!") do if %%~zF EQU 0 set "NEED_CP=1"

if "!NEED_CP!"=="1" (
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo Maven was not found while preparing %~1.
        set "INVALID=1"
        exit /b 0
    )
    echo Preparing runtime classpath for %~1...
    pushd "%ROOT%%~2"
    call mvn -q "-Dmaven.repo.local=!LOCAL_REPO!" "-DincludeScope=runtime" "-DexcludeGroupIds=com.tianji" dependency:build-classpath "-Dmdep.outputAbsoluteArtifactFilename=true" "-Dmdep.outputFile=!CP_FILE!"
    set "MAVEN_ERROR=!errorlevel!"
    popd
    if not "!MAVEN_ERROR!"=="0" (
        echo Failed to prepare runtime classpath for %~1.
        set "INVALID=1"
        exit /b 0
    )
)

set "INTERNAL_CP="
for %%M in (%~4) do set "INTERNAL_CP=!INTERNAL_CP!%ROOT%%%M\target\classes;"
set "EXTERNAL_CP="
<"!CP_FILE!" set /p "EXTERNAL_CP="
if not defined EXTERNAL_CP (
    echo Empty runtime classpath for %~1: "!CP_FILE!"
    set "INVALID=1"
    exit /b 0
)

set "RUNTIME_CP=!INTERNAL_CP!!EXTERNAL_CP!"
set "ARGS_FILE=%ROOT%%~2\target\codex-java.args"
>"!ARGS_FILE!" echo -Dfile.encoding=UTF-8
>>"!ARGS_FILE!" echo -Dspring.output.ansi.enabled=always
>>"!ARGS_FILE!" echo -cp
>>"!ARGS_FILE!" echo "!RUNTIME_CP!"
>>"!ARGS_FILE!" echo %~3
>>"!ARGS_FILE!" echo --spring.profiles.active=!PROFILE!
exit /b 0

:start_service
start "%~1" /D "%ROOT%%~2" "%ComSpec%" /k ""!JAVA_EXE!" @target\codex-java.args"
exit /b 0

:main
rem Start all Spring Boot applications like IntelliJ IDEA.
rem Usage: start-all-services.bat [profile]
rem Check module outputs without starting: start-all-services.bat /check
rem Default profile: local

set "ROOT=%~dp0"
set "PROFILE=%~1"
if not defined PROFILE set "PROFILE=local"
set "CHECK_ONLY=0"
if /i "%~1"=="/check" (
    set "CHECK_ONLY=1"
    set "PROFILE=local"
)

if not defined JAVA_HOME (
    echo JAVA_HOME is not set. Please configure a Java 17 JDK first.
    pause
    exit /b 1
)

set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "!JAVA_EXE!" (
    echo Java was not found at "!JAVA_EXE!".
    pause
    exit /b 1
)

set "LOCAL_REPO=%USERPROFILE%\.m2\repository"
set "INVALID=0"

call :check_service "AuthApplication" "tj-auth\tj-auth-service" "com\tianji\AuthApplication.class" "tj-auth\tj-auth-service tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "CourseApplication" "tj-course" "com\tianji\CourseApplication.class" "tj-course tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "DataCenterApplication" "tj-data" "com\tianji\DataCenterApplication.class" "tj-data tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "ExamApplication" "tj-exam" "com\tianji\ExamApplication.class" "tj-exam tj-common tj-api tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "GatewayApplication" "tj-gateway" "com\tianji\GatewayApplication.class" "tj-gateway tj-auth\tj-auth-gateway-sdk tj-auth\tj-auth-common tj-common"
call :check_service "LearningApplication" "tj-learning" "com\tianji\LearningApplication.class" "tj-learning tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "MediaApplication" "tj-media" "com\tianji\MediaApplication.class" "tj-media tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "MessageApplication" "tj-message\tj-message-service" "com\tianji\MessageApplication.class" "tj-message\tj-message-service tj-api tj-common tj-message\tj-message-domain tj-message\tj-message-api"
call :check_service "PayApplication" "tj-pay\tj-pay-service" "com\tianji\PayApplication.class" "tj-pay\tj-pay-service tj-common tj-pay\tj-pay-domain"
call :check_service "PromotionApplication" "tj-promotion" "com\tianji\PromotionApplication.class" "tj-promotion tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "RemarkApplication" "tj-remark" "com\tianji\RemarkApplication.class" "tj-remark tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "SearchApplication" "tj-search" "com\tianji\SearchApplication.class" "tj-search tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "TradeApplication" "tj-trade" "com\tianji\TradeApplication.class" "tj-trade tj-api tj-common tj-pay\tj-pay-api tj-pay\tj-pay-domain tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :check_service "UserApplication" "tj-user" "com\tianji\UserApplication.class" "tj-user tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common tj-message\tj-message-api tj-message\tj-message-domain"
call :check_service "AIGCApplication" "tj-aigc" "com\tianji\AIGCApplication.class" "tj-aigc tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"

if "!INVALID!"=="1" (
    echo One or more required module outputs are missing. No service was started.
    pause
    exit /b 1
)

if "!CHECK_ONLY!"=="1" (
    echo All application classes and module outputs are valid.
    endlocal
    exit /b 0
)

set "INVALID=0"
call :prepare_service "AuthApplication" "tj-auth\tj-auth-service" "com.tianji.AuthApplication" "tj-auth\tj-auth-service tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "CourseApplication" "tj-course" "com.tianji.CourseApplication" "tj-course tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "DataCenterApplication" "tj-data" "com.tianji.DataCenterApplication" "tj-data tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "ExamApplication" "tj-exam" "com.tianji.ExamApplication" "tj-exam tj-common tj-api tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "GatewayApplication" "tj-gateway" "com.tianji.GatewayApplication" "tj-gateway tj-auth\tj-auth-gateway-sdk tj-auth\tj-auth-common tj-common"
call :prepare_service "LearningApplication" "tj-learning" "com.tianji.LearningApplication" "tj-learning tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "MediaApplication" "tj-media" "com.tianji.MediaApplication" "tj-media tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "MessageApplication" "tj-message\tj-message-service" "com.tianji.MessageApplication" "tj-message\tj-message-service tj-api tj-common tj-message\tj-message-domain tj-message\tj-message-api"
call :prepare_service "PayApplication" "tj-pay\tj-pay-service" "com.tianji.PayApplication" "tj-pay\tj-pay-service tj-common tj-pay\tj-pay-domain"
call :prepare_service "PromotionApplication" "tj-promotion" "com.tianji.PromotionApplication" "tj-promotion tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "RemarkApplication" "tj-remark" "com.tianji.RemarkApplication" "tj-remark tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "SearchApplication" "tj-search" "com.tianji.SearchApplication" "tj-search tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "TradeApplication" "tj-trade" "com.tianji.TradeApplication" "tj-trade tj-api tj-common tj-pay\tj-pay-api tj-pay\tj-pay-domain tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"
call :prepare_service "UserApplication" "tj-user" "com.tianji.UserApplication" "tj-user tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common tj-message\tj-message-api tj-message\tj-message-domain"
call :prepare_service "AIGCApplication" "tj-aigc" "com.tianji.AIGCApplication" "tj-aigc tj-api tj-common tj-auth\tj-auth-resource-sdk tj-auth\tj-auth-common"

if "!INVALID!"=="1" (
    echo Failed to prepare one or more runtime classpaths. No service was started.
    pause
    exit /b 1
)

call :start_service "AuthApplication" "tj-auth\tj-auth-service"
call :start_service "CourseApplication" "tj-course"
call :start_service "DataCenterApplication" "tj-data"
call :start_service "ExamApplication" "tj-exam"
call :start_service "GatewayApplication" "tj-gateway"
call :start_service "LearningApplication" "tj-learning"
call :start_service "MediaApplication" "tj-media"
call :start_service "MessageApplication" "tj-message\tj-message-service"
call :start_service "PayApplication" "tj-pay\tj-pay-service"
call :start_service "PromotionApplication" "tj-promotion"
call :start_service "RemarkApplication" "tj-remark"
call :start_service "SearchApplication" "tj-search"
call :start_service "TradeApplication" "tj-trade"
call :start_service "UserApplication" "tj-user"
call :start_service "AIGCApplication" "tj-aigc"

echo All service windows have been opened with profile "!PROFILE!".
echo Each window runs the compiled main class directly, like IntelliJ IDEA.
endlocal
exit /b 0
