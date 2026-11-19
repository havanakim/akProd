param([string]$SourceDirectory='D:/Pictures/공정관리')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$projectPath=Split-Path $PSScriptRoot -Parent
$assetPath=Join-Path $projectPath 'app/src/androidTest/assets/ocr'
New-Item -ItemType Directory -Force -Path $assetPath | Out-Null
$cases=[Collections.Generic.List[object]]::new()
function Add-Case($photo,$key,$expected,$x,$y,$hw=32,$hh=12) {
    $cases.Add([pscustomobject]@{source=$photo;key=$key;expected=$expected;x=$x;y=$y;hw=$hw;hh=$hh})
}
function Add-Row($photo,$prefix,$expected,$xs,$ys,$hw=32,$hh=12) {
    for($i=0;$i -lt $expected.Count;$i++) { Add-Case $photo ($prefix+(5-$i)) $expected[$i] $xs[$i] $ys[$i] $hw $hh }
}
# Coordinates are manually read in a 900x1200 EXIF-oriented preview; runtime OCR never uses these answers.
$p='20261116_090335'
Add-Row $p 'IP' @('096','092','090','062','038') @(322,398,473,547,624) @(401,401,401,401,401)
Add-Row $p 'IV' @('27.0','25.0','20.0','04.3','03.3') @(328,403,478,551,625) @(447,447,447,447,447)
Add-Row $p 'HP' @('000','000','000','000','075') @(327,400,473,546,621) @(512,512,512,512,512)
Add-Row $p 'HT' @('000.0','000.0','000.0','000.0','020.0') @(327,400,473,545,620) @(554,554,554,554,554) 34 11
$p='20261116_090329'
Add-Row $p 'IP' @('096','092','090','062','038') @(305,379,453,525,600) @(429,429,429,429,429)
Add-Row $p 'IV' @('27.0','25.0','20.0','04.3','03.3') @(310,382,456,529,603) @(473,473,473,473,473)
Add-Row $p 'HP' @('000','000','000','000','075') @(309,381,453,525,599) @(535,535,535,535,535)
Add-Row $p 'HT' @('000.0','000.0','000.0','000.0','020.0') @(303,376,449,521,594) @(575,575,575,575,575) 34 11
$p='20261116_090348'
Add-Row $p 'S' @('010.0','050.0','100.0','130.0','148.0') @(327,405,485,565,647) @(479,479,479,479,479) 34 12
Add-Row $p 'RV' @('24.0','24.0','24.0','25.0','25.0') @(287,367,443,524,603) @(524,524,524,524,524) 44 14
Add-Row $p 'BP' @('20','20','20','20','20') @(286,366,443,520,601) @(569,569,569,569,569) 25 11
$p='20261116_090252'
Add-Row $p 'S' @('010.0','050.0','100.0','130.0','148.0') @(264,325,387,449,512) @(394,394,394,394,394) 29 10
Add-Row $p 'RV' @('24.0','24.0','24.0','25.0','25.0') @(226,287,347,407,469) @(430,430,430,430,430) 36 12
Add-Row $p 'BP' @('20','20','20','20','20') @(228,288,348,409,470) @(465,465,465,465,465) 23 9
$p='20261116_090526'
Add-Case $p 'HOLD_3_PRESSURE' '0' 294 585 17 13
Add-Case $p 'HOLD_3_TIME' '0.0' 280 667 31 14
Add-Case $p 'HOLD_2_PRESSURE' '0' 391 586 17 13
Add-Case $p 'HOLD_2_TIME' '0.0' 381 668 31 14
Add-Case $p 'HOLD_1_PRESSURE' '78' 484 588 24 14
Add-Case $p 'HOLD_1_SPEED' '8' 480 630 18 14
Add-Case $p 'HOLD_1_TIME' '25.0' 474 673 34 15
foreach($col in @(@(5,603,592,592,633,592,674,590,718,'78','11','30.0','23.0'),@(4,712,593,705,636,702,678,699,723,'76','10','30.0','48.0'),@(3,820,596,815,639,814,683,812,728,'66','5','30.0','100.0'))) {
    $keys=@('PRESSURE','SPEED','TIME','POSITION')
    for($i=0;$i -lt 4;$i++){Add-Case $p ('INJECT_'+$col[0]+'_'+$keys[$i]) $col[9+$i] $col[1+$i*2] $col[2+$i*2] $(if($i -lt 2){23}else{39}) 14}
}
Add-Case $p 'CONTROL_9' '51.9' 659 1124 39 16
$p='20261116_090531'
foreach($col in @(@(5,191,527,184,571,186,612,186,653,'78','11','30.0','23.0'),@(4,285,524,277,569,282,610,282,653,'76','10','30.0','48.0'),@(3,381,523,376,568,383,609,383,654,'66','5','30.0','100.0'),@(2,489,522,481,567,486,611,486,654,'40','2','20.0','138.0'),@(1,601,521,589,567,592,611,592,655,'22','2','20.0','160.0'))) {
    $keys=@('PRESSURE','SPEED','TIME','POSITION')
    for($i=0;$i -lt 4;$i++){Add-Case $p ('INJECT_'+$col[0]+'_'+$keys[$i]) $col[9+$i] $col[1+$i*2] $col[2+$i*2] $(if($i -lt 2){23}else{40}) 15}
}
Add-Case $p 'HOLD_1_PRESSURE' '78' 88 528 22 14
Add-Case $p 'HOLD_1_SPEED' '8' 83 571 16 14
Add-Case $p 'HOLD_1_TIME' '25.0' 87 611 31 14
Add-Case $p 'CONTROL_9' '51.9' 245 1030 33 16
$p='20261116_090538'
Add-Case $p 'HOLD_3_PRESSURE' '0' 181 385 12 12
Add-Case $p 'HOLD_3_TIME' '0.0' 174 452 23 13
Add-Case $p 'HOLD_2_PRESSURE' '0' 258 384 12 12
Add-Case $p 'HOLD_2_TIME' '0.0' 249 452 24 13
Add-Case $p 'HOLD_1_PRESSURE' '78' 328 383 17 12
Add-Case $p 'HOLD_1_SPEED' '8' 326 416 13 13
Add-Case $p 'HOLD_1_TIME' '25.0' 324 452 25 13
foreach($col in @(@(5,415,380,407,416,407,452,407,484,'78','11','30.0','23.0'),@(4,498,378,489,414,489,451,489,484,'76','10','30.0','48.0'),@(3,577,376,573,412,574,449,570,483,'66','5','30.0','100.0'),@(2,662,374,656,410,655,447,652,482,'40','2','20.0','138.0'),@(1,744,372,740,407,739,446,735,480,'22','2','20.0','160.0'))) {
    $keys=@('PRESSURE','SPEED','TIME','POSITION')
    for($i=0;$i -lt 4;$i++){Add-Case $p ('INJECT_'+$col[0]+'_'+$keys[$i]) $col[9+$i] $col[1+$i*2] $col[2+$i*2] $(if($i -lt 2){19}else{30}) 13}
}
Add-Case $p 'CONTROL_9' '51.9' 455 798 29 15
$manifest=[Collections.Generic.List[object]]::new()
foreach($group in ($cases | Group-Object source)) {
    $image=[Drawing.Image]::FromFile((Join-Path $SourceDirectory ($group.Name+'.jpg')))
    try {
        if($image.PropertyIdList -contains 274 -and [BitConverter]::ToUInt16($image.GetPropertyItem(274).Value,0) -eq 6){$image.RotateFlip([Drawing.RotateFlipType]::Rotate90FlipNone)}
        $bitmap=[Drawing.Bitmap]::new($image)
        try {
            foreach($case in $group.Group) {
                $left=[int][math]::Round(($case.x-$case.hw)*$bitmap.Width/900)
                $top=[int][math]::Round(($case.y-$case.hh)*$bitmap.Height/1200)
                $width=[int][math]::Round($case.hw*2*$bitmap.Width/900)
                $height=[int][math]::Round($case.hh*2*$bitmap.Height/1200)
                $rect=[Drawing.Rectangle]::new($left,$top,$width,$height)
                $crop=$bitmap.Clone($rect,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
                $id=$case.source+'_'+$case.key
                try{$crop.Save((Join-Path $assetPath ($id+'.png')),[Drawing.Imaging.ImageFormat]::Png)}finally{$crop.Dispose()}
                $manifest.Add([ordered]@{id=$id;source=($case.source+'.jpg');key=$case.key;expected=$case.expected;rect=@($left,$top,$width,$height)})
            }
        }finally{$bitmap.Dispose()}
    }finally{$image.Dispose()}
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $assetPath 'cases.json') -Encoding utf8
Write-Output ('Prepared '+$manifest.Count+' labeled crops from '+($cases.source|Select-Object -Unique).Count+' original photos')
