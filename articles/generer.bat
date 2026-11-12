@echo off
setlocal
cd /d "%~dp0"
py -3 generer.py
set "RESULTAT=%ERRORLEVEL%"
if "%RESULTAT%"=="0" (
    echo.
    echo Appuyez sur une touche pour fermer cette fenetre.
) else (
    echo.
    echo Echec de la generation. Code : %RESULTAT%
    echo Appuyez sur une touche pour lire les erreurs et fermer cette fenetre.
)
pause >nul
exit /b %RESULTAT%
