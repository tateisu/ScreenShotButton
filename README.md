# ScreenShotButton
Android app that shows overlay screen shot button

### 作成した動機
ソシャゲのスクショを撮るときに、不器用なので電源＋音量下ボタンの同時押しに失敗して画面が先に進んでしまうので、撮影ボタンを画面上にオーバーレイ表示するタイプのスクリーンショット撮影ソフトを使っていました。しかしとあるタブレットで「撮影できたけど内容がブランクイメージだった」が発生します。結果をすぐに表示するタイプの撮影アプリで手動リトライしてたんですが、あまりにも頻出するので手間を省くためにアプリを自分で書いてみました。

### 特徴
- Android用のスクリーンショット撮影アプリです。
- 撮影ボタンを画面上にオーバーレイ表示するタイプ。
- MediaProjectionを使った画面キャプチャで起こりがちな「取得できたけど内容がブランクイメージだった」を検出してリトライします。

### 動作確認済み機種
- API 29 Android 10 Essential PH-1 Phone 
- API 28 Android 9 Huawei CMR-W19 MediaPad M5 Pro 
- API 27 Android 8.1 Google bullhead Nexus 5X (2015,Googleストア版) 
- API 26 Android 8.0 Sony SO-02J Xperia X Compact (docomo) 
- API 25 Android 7.1.1 Kyocera 602KC Digno G 
- API 24 Android 7.0 Huawei HDN-W09 MediaPad M3 Lite 10 wp 
- API 23 Android 6.0.1 Sony SO-02G Xperia Z3 compact (docomo) 
- API 22 Android 5.1 Aubee CP-B43-Ab elm. 
- API 21 Android 5.0.2 LGE LGL24 isai FL

### 欠点
- 静止画しか撮れません。
- 負荷を軽くするため、遅延はやや大きめです。リトライが主眼なのでまあ仕方ないね

## スクリーンショット

画面の左上にある絞りの形をしたのが撮影ボタンです。ドラッグで移動できます。
![Screenshot_20191221_232648_jp juggler screenshotbutton](https://user-images.githubusercontent.com/333944/71309445-08fa7400-244b-11ea-9dba-94005e2dc28b.jpg)

ボタンを押した後少し待つと撮影結果を確認できます。
![Screenshot_20191221_232724_jp juggler screenshotbutton](https://user-images.githubusercontent.com/333944/71309446-0b5cce00-244b-11ea-84c6-180f9b7e562c.jpg)
