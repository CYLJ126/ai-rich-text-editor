# ARTE Backend


## 附录

### 启停脚本

**Linux 启动**

```bash
#!/bin/bash
processId=`ps -ef|grep arte-app|grep -v 'grep'|awk '{print $2}'`
for id in ${processId}
 do
 kill -9 $id
 echo "process killed, pid: ${id}"
done
sleep 3
nohup java  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=7500 -jar arte-app-boot.jar >> app.log &
```

**Linux 停止**

```bash
#!/bin/bash
processId=`ps -ef|grep arte-app|grep -v 'grep'|awk '{print $2}'`
for id in ${processId}
 do
 kill -9 $id
 echo "process killed, pid: ${id}"
done
```

**Windows 启动**

```cmd
CHCP 65001
"C:\Program Files\Java\jdk-21\bin\java.exe" -jar ./target/arte-app-boot.jar
```

**Windows 停止**

```cmd
@echo off
REM 查找占用端口 12636 的进程 PID
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":12636" ^| findstr "LISTENING"') do (
    set PID=%%a
)

REM 检查是否找到了 PID
if "%PID%"=="" (
    echo 没有找到占用端口 12636 的 Java 应用程序。
) else (
    echo 找到占用端口 12636 的进程，PID 为：%PID%
    echo 正在终止该进程...
    taskkill /PID %PID% /F
    if %errorlevel% equ 0 (
        echo 进程已成功终止。
    ) else (
        echo 终止进程时出错。
    )
)
pause
```

### 密钥生成

```bash
# 生成一套 RSA 2048 密钥对，用于配置 security.sm2.privateKey/security.sm2.publicKey
ssh-keygen -t rsa -b 2048 -C "your-email@example.com"
```

```bash
# 生成一个对称密钥，用于配置 jwt.base64-secret-key
openssl rand -base64 66
```
