@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
title FitMind 本地启动器

REM ============================================================
REM  FitMind 双击启动器（Windows）
REM ------------------------------------------------------------
REM  用途：把「起中间件 → 起 15 个服务 → 起前端」包成一次双击，
REM        省掉手工开 Git Bash、敲 4 段命令的麻烦。
REM
REM  用法（双击 = 默认启动；也可以在本文件所在目录开 cmd 传参）：
REM    start-local.cmd           启动全部（中间件 + 15 服务 + 2 前端）
REM    start-local.cmd stop      停掉全部
REM    start-local.cmd status    只查端口状态
REM
REM  ★ 为什么最后还是得借 Git Bash：项目的 scripts/*.sh 是 POSIX 脚本，
REM    用了 /dev/tcp、iconv、awk、export 这些 cmd 里根本不存在的东西。
REM    本脚本只是把「怎么调 Git Bash」这件事替你藏起来，并不改变这个前提。
REM  ★ 换机器时只需改下面的 GIT_BASH_CANDIDATES 列表。
REM ============================================================

REM ---- 项目根目录 = 本文件所在目录的上一级 ----
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
cd /d "%ROOT%" || (echo [X] 无法进入项目目录 & pause & exit /b 1)

REM ---- 1) 找 Git Bash（换机器改这里；找不到时回退到 where bash）----
set "BASH="
for %%P in (
  "D:\develop\Git\bin\bash.exe"
  "%ProgramFiles%\Git\bin\bash.exe"
  "%ProgramFiles(x86)%\Git\bin\bash.exe"
  "%LocalAppData%\Programs\Git\bin\bash.exe"
) do (
  if not defined BASH if exist "%%~P" set "BASH=%%~P"
)
if not defined BASH (
  for /f "delims=" %%I in ('where bash.exe 2^>nul') do (
    if not defined BASH set "BASH=%%I"
  )
)
if not defined BASH (
  echo.
  echo [X] 找不到 Git Bash。
  echo     请确认已安装 Git for Windows，或手工修改本文件里的路径列表。
  echo     https://git-scm.com/download/win
  echo.
  pause
  exit /b 1
)

REM ---- 2) 环境变量：★ 限堆 —— 漏了这行 15 个服务会随机挂几个 ----
set "JAVA_TOOL_OPTIONS=-Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC"
if not defined AI_MODE set "AI_MODE=mock"

REM ---- 3) 参数分支 ----
if /i "%~1"=="stop"   goto DO_STOP
if /i "%~1"=="status" goto DO_STATUS

REM ============================================================
REM  启动
REM ============================================================
echo.
echo ============================================================
echo   FitMind 本地启动
echo ============================================================
echo   项目目录 : %ROOT%
echo   Git Bash : %BASH%
echo   限堆参数 : %JAVA_TOOL_OPTIONS%
echo   AI 通道  : %AI_MODE%
echo ============================================================
echo.

echo [1/3] 起中间件（6 个容器）...
pushd docker
docker-compose -p fitmind up -d
if errorlevel 1 (
  echo.
  echo [X] 中间件启动失败。最常见原因：
  echo     - Docker Desktop 没开（先把它点开，等托盘图标变绿）
  echo     - 用了 "docker compose"（空格）—— 本机只支持 "docker-compose"（带横线）
  echo.
  popd
  pause
  exit /b 1
)
popd
echo.

echo [2/3] 起前端（会另开两个窗口）...
REM ★ 直接跑 vite 的垫片，不走 "npm run dev"：
REM   在 Git Bash 里 npm 派生出的子 shell 会找不到 node/npm
REM   （报「'npm' 不是内部或外部命令」或「'"node"' 不是内部或外部命令」），
REM   故统一绕开 npm。详见 docs/本地启动手册.md §2.4
start "FitMind portal 5173" /d "%ROOT%\frontend\fitmind-portal" cmd /k "node_modules\.bin\vite.cmd"
start "FitMind admin  5174" /d "%ROOT%\frontend\fitmind-admin" cmd /k "node_modules\.bin\vite.cmd"
echo.

echo [3/3] 起 15 个业务服务（串行，每个约 20~30 秒）...
echo       ★ 本窗口会一直占着 —— 关掉它 = 停掉全部服务。
echo.
echo ------------------------------------------------------------
echo   登录入口（验证码自动回填，无需短信）
echo     学员   13800010001   http://localhost:5173
echo     教练   13800010002   http://localhost:5174
echo     管理员 13800010003   http://localhost:5174
echo   ★ 两端账号类型相反：学员端只能学员登，管理端只能教练/管理员登
echo ------------------------------------------------------------
echo.

"%BASH%" scripts/start-group.sh fm-auth fm-user fm-media fm-course fm-search fm-learning fm-remark fm-trade fm-pay fm-promotion fm-message fm-exam fm-data fm-aigc fm-gateway

REM start-group.sh 末尾 wait 持有本窗口；正常不会走到这里
echo.
echo [!] 服务已全部退出（或启动器被中断）。
pause
exit /b 0

REM ============================================================
REM  停服务
REM ============================================================
:DO_STOP
echo.
echo ===== 停掉全部服务（后端 15 个 + 前端 2 个）=====
"%BASH%" scripts/stop-service.sh --all
echo.
pause
exit /b 0

REM ============================================================
REM  查状态
REM ============================================================
:DO_STATUS
echo.
echo ===== 端口状态（★ 端口通 != 服务就绪，详见 docs/本地启动手册.md §3）=====
powershell -NoProfile -Command "$ports=8081,8082,8083,8084,8085,8086,8087,8088,8089,8090,8091,8092,8093,8094,10010,5173,5174; $up=0; foreach($p in $ports){ $c=New-Object Net.Sockets.TcpClient; try{ $c.Connect('127.0.0.1',$p); 'OK   ' + $p; $up++ } catch { 'FAIL ' + $p } finally { $c.Dispose() } }; ''; ('=> ' + $up + '/' + $ports.Count + ' 在线')"
echo.
echo ===== 中间件容器 =====
docker ps --format "{{.Names}}  {{.Status}}"
echo.
echo ===== RabbitMQ 连接数（空 = 服务"假起来了"，环境变量没注入）=====
docker exec fm-rabbitmq rabbitmqctl list_connections user
echo.
pause
exit /b 0
