# Maleicacid TV Input Framework 視聴

## 接続先とスコープ

この接続は [kazuki0824/tv PR #116](https://github.com/kazuki0824/tv/pull/116) の
`com.maleicacid.tvinput.tis.MaleicacidTvInputService` に対して、Android標準の
`TvInputManager` / `TvContract` / `TvView` のみを使用する。
KomorebiからTuner SDKやTuner HAL Binderを直接呼ばない。

- 視聴経路: `TvView.tune(TvInputInfo.id, TvContract.buildChannelUri(rowId))`
- チャンネルと番組表: 通常アプリに公開されたTvProvider行のみ
- 入力ID: `TvInputManager.tvInputList` からサービスのComponentNameで発見する。パッケージ名から固定生成しない
- チャンネルのID: `tif:<TvProvider row _id>`（ネットワーク識別子と混同しない）
- ワンセグと未セットアップ行: `browsable=0`を表示しない
- private provider-data、CAS情報、frequency、driver固有selectorは読まない
- 録画・予約: #116 の `canRecord=false` に合わせて非対応。KonomiTV/EDCBなどへ誤転送しない
- 既存のMedia3 / ネットワーク配信は変更せず、TIF選択時だけ独立したTvView経路へ切り替える

## 利用手順

1. #116 のTISが組み込まれ、OSが `TvInputManager` 経由で発見できることを確認する。
2. Komorebiを通常の `adb install -r` で導入する。
3. 設定 > 利用するシステムで「TV Input Framework (Maleicacid)」を選択する。
4. 同設定画面の「TV入力のチャンネルスキャン」を実行してチャンネルを登録する。
5. `READ_TV_LISTINGS` の実行時要求を許可し、ライブ画面から視聴する。

権限は `android.permission.READ_TV_LISTINGS` のみであり、
`ACCESS_ALL_EPG_DATA`、`ACCESS_TV_TUNER`、`TUNER_RESOURCE_ACCESS`
をKomorebi側へ要求しない。

## 受け入れ確認（未実行）

- 通常 `adb install` したAPKが実行時権限許可後に#116のinputIdを発見する。
- 未導入TIS・権限拒否・TvProvider query失敗時は空データを正常結果として扱わない。
- #116のsetup完了後にbrowsableな地上/BS/CSを一覧へ表示できる。
- `browsable=0` のワンセグやスキャン途中行は表示しない。
- 番組表が公開 `TvContract.Programs` から読み取れる。
- 選局、再選局、画面終了の際の`TvView`セッションが正しく解放される。
- 視聴不能/切断をユーザーに通知し、以前の映像を成功と見なさない。
- 標準LiveTvとチューナーの資源競合があっても既存のTIS通知を表示できる。
- 通常ネットワークバックエンドのライブ/録画/予約経路が維持される。
- TIF選択時の録画/予約実行が別バックエンドへ流れない。
- 実機の音声・字幕・成人向け視聴制限・リモコン操作・バックグラウンド復帰を確認する。

Android SDK / NDK・実機のない環境ではビルド、インストール、CTS、実波試験は実施できない。
その場合は本PRを実機確認待ちとして扱い、完了・マージ可能とは断言しない。
