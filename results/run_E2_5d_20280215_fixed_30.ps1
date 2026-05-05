$ErrorActionPreference = 'Stop'
Set-Location 'C:\Users\andre\Downloads\aco-tasf'
New-Item -ItemType Directory -Force -Path results | Out-Null
function Get-MatchValue($text, $pattern, $group = 1) {
    $m = [regex]::Match($text, $pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($m.Success) { return $m.Groups[$group].Value.Trim() }
    return ""
}
$progressPath = 'results\E2_5d_20280215_fixed_30.progress.txt'
Set-Content -Path $progressPath -Value "started $(Get-Date -Format o)" -Encoding UTF8
$rows = @()
for ($s = 1; $s -le 30; $s++) {
    Add-Content -Path $progressPath -Value "running replica=$s seed=$s start=$(Get-Date -Format o)"
    $output = (& java "-Dtasf.seed=$s" -cp target/classes pe.edu.pucp.tasf.Main E2 5 20280215 0 2>&1) -join "`n"
    Set-Content -Path ("results\E2_5d_20280215_fixed_seed_{0}.txt" -f $s) -Value $output -Encoding UTF8
    $row = [pscustomobject]@{
        Replica = $s
        Seed = $s
        TotalRequests = Get-MatchValue $output 'Total requests:\s+(\d+)'
        TotalSuitcases = Get-MatchValue $output 'Total suitcases:\s+(\d+)'
        Delivered = Get-MatchValue $output 'Delivered:\s+(\d+)'
        Undelivered = Get-MatchValue $output 'Undelivered:\s+(\d+)'
        LateSuitcases = Get-MatchValue $output 'Late suitcases:\s+(\d+)'
        TotalDelay = Get-MatchValue $output 'Total delay:\s+([0-9.]+\s+days)'
        CapacityOverflow = Get-MatchValue $output 'Capacity overflow:\s+(\d+)'
        WarehouseOverflow = Get-MatchValue $output 'Warehouse overflow:\s*(\d+)'
        Fitness = Get-MatchValue $output 'Fitness:\s+([0-9.]+)'
        Semaphore = Get-MatchValue $output 'Semaphore:\s+(\w+)'
        IterationsRun = Get-MatchValue $output 'Iterations run:\s+(\d+)'
        Improvements = Get-MatchValue $output 'Improvements:\s+(\d+)'
        ExecutionTime = Get-MatchValue $output 'Iterations:\s+\d+, Improvements:\s+\d+, Time:\s+(\d+\s+ms)'
    }
    $rows += $row
    $rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path results\E2_5d_20280215_fixed_30_runs.partial.csv
    Add-Content -Path $progressPath -Value "done replica=$s seed=$s fitness=$($row.Fitness) late=$($row.LateSuitcases) wh=$($row.WarehouseOverflow) time=$($row.ExecutionTime) end=$(Get-Date -Format o)"
}
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path results\E2_5d_20280215_fixed_30_runs.csv
$md = @()
$md += '| Réplica | Seed | Total requests | Total suitcases | Delivered | Undelivered | Late suitcases | Total delay | Capacity overflow | Warehouse overflow | Fitness | Semaphore | Iterations run | Improvements | Tiempo ejecución |'
$md += '| ------: | ---: | -------------: | --------------: | --------: | ----------: | -------------: | ----------: | ----------------: | -----------------: | ------: | --------- | -------------: | -----------: | ---------------: |'
foreach ($r in $rows) {
    $md += "| $($r.Replica) | $($r.Seed) | $($r.TotalRequests) | $($r.TotalSuitcases) | $($r.Delivered) | $($r.Undelivered) | $($r.LateSuitcases) | $($r.TotalDelay) | $($r.CapacityOverflow) | $($r.WarehouseOverflow) | $($r.Fitness) | $($r.Semaphore) | $($r.IterationsRun) | $($r.Improvements) | $($r.ExecutionTime) |"
}
Set-Content -Path results\E2_5d_20280215_fixed_30_runs.md -Value $md -Encoding UTF8
Add-Content -Path $progressPath -Value "finished $(Get-Date -Format o)"
