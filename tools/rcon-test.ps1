param(
    [string]$HostName = '127.0.0.1',
    [int]$Port = 25575,
    [string]$Password = 'test123',
    [string[]]$Commands = @()
)

$ErrorActionPreference = 'Stop'

function Send-Packet {
    param($Stream, [int]$Id, [int]$Type, [string]$Body)
    $payload = [System.Text.Encoding]::ASCII.GetBytes($Body)
    $length = 4 + 4 + $payload.Length + 2
    $buffer = New-Object byte[] (4 + $length)
    [System.BitConverter]::GetBytes([int]$length).CopyTo($buffer, 0)
    [System.BitConverter]::GetBytes([int]$Id).CopyTo($buffer, 4)
    [System.BitConverter]::GetBytes([int]$Type).CopyTo($buffer, 8)
    $payload.CopyTo($buffer, 12)
    $buffer[$buffer.Length - 2] = 0
    $buffer[$buffer.Length - 1] = 0
    $Stream.Write($buffer, 0, $buffer.Length)
    $Stream.Flush()
}

function Read-Packet {
    param($Stream)
    $lenBytes = New-Object byte[] 4
    $read = $Stream.Read($lenBytes, 0, 4)
    if ($read -lt 4) { return $null }
    $length = [System.BitConverter]::ToInt32($lenBytes, 0)
    if ($length -le 0 -or $length -gt 1MB) { return $null }
    $buffer = New-Object byte[] $length
    $offset = 0
    while ($offset -lt $length) {
        $n = $Stream.Read($buffer, $offset, $length - $offset)
        if ($n -le 0) { break }
        $offset += $n
    }
    $id = [System.BitConverter]::ToInt32($buffer, 0)
    $type = [System.BitConverter]::ToInt32($buffer, 4)
    $bodyLength = $length - 10
    $body = if ($bodyLength -gt 0) { [System.Text.Encoding]::ASCII.GetString($buffer, 8, $bodyLength) } else { '' }
    return [pscustomobject]@{ Id = $id; Type = $type; Body = $body }
}

$client = New-Object System.Net.Sockets.TcpClient
$client.ReceiveTimeout = 5000
$client.Connect($HostName, $Port)
$stream = $client.GetStream()

Send-Packet -Stream $stream -Id 1 -Type 3 -Body $Password
$auth = Read-Packet -Stream $stream
if (-not $auth) { throw 'RCON authentication failed: no response' }
if ($auth.Id -eq -1) { throw 'RCON authentication failed: bad password' }

$authId = 10
foreach ($command in $Commands) {
    Send-Packet -Stream $stream -Id $authId -Type 2 -Body $command
    $response = Read-Packet -Stream $stream
    $text = if ($response) { $response.Body } else { '<no response>' }
    Write-Output ("CMD: {0}`n{1}`n---" -f $command, $text)
    $authId++
}

$client.Close()
