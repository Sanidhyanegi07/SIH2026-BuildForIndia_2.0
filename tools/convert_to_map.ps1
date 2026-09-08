$regions = @{
    "uttarakhand" = @{ file = "uttarakhand-latest.osm.pbf"; bbox = "28.7,77.5,31.5,81.1" }
    "himachal_pradesh" = @{ file = "himachal_pradesh-latest.osm.pbf"; bbox = "30.3,75.5,33.3,79.0" }
    "haryana" = @{ file = "haryana-latest.osm.pbf"; bbox = "27.6,74.4,30.9,77.6" }
    "uttar_pradesh" = @{ file = "uttar_pradesh-latest.osm.pbf"; bbox = "23.8,77.0,30.4,84.6" }
}

foreach ($region in $regions.GetEnumerator()) {
    $regionId = $region.Name
    $pbfFile = $region.Value.file
    $bbox = $region.Value.bbox
    
    $outDir = "output\$regionId"
    if (!(Test-Path -Path $outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir
    }
    
    $outFile = "$outDir\state.map"
    if (Test-Path -Path $outFile) {
        Write-Host "Skipping $regionId, state.map already exists."
        continue
    }
    
    Write-Host "Converting $regionId with bbox $bbox..."
    & tools\osmosis\bin\osmosis.bat --read-pbf file="source\$pbfFile" --mapfile-writer file="$outFile" bbox=$bbox type=hd
}
Write-Host "Conversion complete!"
