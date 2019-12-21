# ScreenShotButton
Android app that shows overlay screenshot button


### 作成した動機
ソシャゲのスクショを撮るときに、不器用なので電源＋音量下ボタンの同時押しに失敗して画面が先に進んでしまうので、撮影ボタンを画面上にオーバーレイ表示するタイプのスクリーンショット撮影ソフトを使っていたのですが、今使っているタブレット(Huawei MediaPad M5 Pro)だと「撮影できたけど内容がブランクイメージだった」が発生します。ポストビューをすぐに表示できるタイプの撮影アプリだとその場で気が付くので手動リトライしてたんですが、あまりにも頻出するので手間を省くために撮影アプリを自分で書いてみました。


### 特徴
- Android用のスクリーンショット撮影アプリです。
- 撮影ボタンを画面上にオーバーレイ表示するタイプ。
- MediaProjectionを使った画面キャプチャで起こりがちな「取得できたけど内容がブランクイメージだった」を検出してリトライします。
- Huawei MediaPad M5 Pro(Android 9)でブランクイメージの自動検出と自動リトライはうまく動いてます。

### 欠点
- 保存フォルダを指定できません。Android 11で制限がキツくなるのが分かってるのでやる気がない。
- 静止画しか撮れません。
- Android 10(Q)での動作は確認していません。ストレージ関連の制限がキツくなったので多分なにか問題があるでしょう。

## スクリーンショット

画面の左上にある絞りの形をしたのが撮影ボタンです。ドラッグで移動できます。
![Screenshot_20191221_232648_jp juggler screenshotbutton](https://user-images.githubusercontent.com/333944/71309445-08fa7400-244b-11ea-9dba-94005e2dc28b.jpg)

ボタンを押した後少し待つと撮影結果を確認できます。
![Screenshot_20191221_232724_jp juggler screenshotbutton](https://user-images.githubusercontent.com/333944/71309446-0b5cce00-244b-11ea-84c6-180f9b7e562c.jpg)
