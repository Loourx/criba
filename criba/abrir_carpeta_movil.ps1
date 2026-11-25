# Abre en el Explorador una carpeta del móvil conectado por USB ("Transferencia de archivos").
# Uso: powershell -NoProfile -ExecutionPolicy Bypass -File abrir_carpeta_movil.ps1 -Carpeta Criba_para_borrar
param([string]$Carpeta = "")
$shell = New-Object -ComObject Shell.Application
$pc = $shell.NameSpace(17)   # Este equipo
# Los móviles aparecen como dispositivos portátiles: no son unidades del sistema de archivos.
$movil = $pc.Items() | Where-Object { -not $_.IsFileSystem -and $_.IsFolder } | Select-Object -First 1
if (-not $movil) { Write-Output "NO_MOVIL"; exit 1 }
$alm = $movil.GetFolder.Items() | Select-Object -First 1   # Almacenamiento interno compartido
if (-not $alm) { Write-Output "NO_ALMACENAMIENTO"; exit 1 }
$destino = $alm
if ($Carpeta) {
    $sub = $alm.GetFolder.Items() | Where-Object { $_.Name -eq $Carpeta } | Select-Object -First 1
    if ($sub) { $destino = $sub } else { Write-Output "NO_CARPETA" }
}
Start-Process explorer.exe -ArgumentList ('"' + $destino.Path + '"')
Write-Output ("OK " + $destino.Name)
