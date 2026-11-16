# TV Input Framework 視聴機能

## 目的・境界

KomorebiがAndroid標準TV Input Framework（TIF）を経由して、OSに登録された任意の
`TvInputService` を視聴できるようにする。実装は特定のTIS実装、package名、HAL、
チューナーdriver、CAS、独自provider-data schemaを前提にしない。

最初の受入れ対象として [kazuki0824/tv PR #116](https://github.com/kazuki0824/tv/pull/116) の
`MaleicacidTvInputService` を使用する。**接続先を#116に限定する契約ではない。**

## 実装

- 入力の列挙は `TvInputManager.tvInputList`。ユーザーが非表示にした入力は列挙しない。
- 選局対象はinputIdとTvProvider row IDを一組で識別する。新しいrow IDが旧チャンネルのものと同じでも、所有inputIdの一致を確認してから選局する。
- チューナー入力：読み取りが許可された `TvContract.Channels` を列挙し、`COLUMN_INPUT_ID` を現在の登録入力と照合する。閲覧不可/検索不可の行はUIに表示しない。
- パススルー入力（HDMI等）：TvProvider上に実channel rowを持たないため、`TvInputInfo.isPassthroughInput` を確認し `TvContract.buildChannelUriForPassthroughInput(inputId)` で選局する。
- ライブ映像：`TvView.tune(inputId, channelUri)` を使用し、Media3 URLと混同しない。字幕有効化・利用不能/接続切断・視聴年齢制限をTIF標準callbackで扱う。
- 終了・バックグラウンド移行で`TvView`を破棄してセッションを解放し、復帰後は新しいセッションで再選局する。
- チャンネル分類：地上ISDB・衛星ISDB・その他のTVチャンネル・外部入力をUIに区分する。入力側が公開した`browsable`と`searchable`だけを使用し、ワンセグ等の固有サービス種別を強制除外しない。
- 番組表：許可された公開`TvContract.Programs`を既存EPGモデルへ変換する。番組を公開しないTISでも、チャンネル選局は可能にする。
- TIFの録画・予約はKomorebi側で未実装。選択されたTISに`canRecord=true`があっても、Komorebiに録画機能が自動で追加されるわけではない。
- ネットワークバックエンド（KonomiTV、EDCB、EPGStation）については再生経路を変更しない。
- EPGキャッシュはバックエンドごとに分離し、切替時にはViewModel内の表示データを更新する。

## インストールと利用

1. 利用したいTISをAndroid TV製品に導入して、`TvInputManager`が入力を認識することを確認する。
2. 通常の `adb install -r` でKomorebiを導入する（Komorebiのpriv-app化は行わない）。
3. 設定の「利用するシステム」で「TV Input Framework」を選択する。
4. 設定の「TV入力のチャンネルスキャン」で、セットアップが必要な入力を選び、各TISのセットアップ画面を開く。パススルーのみなら走査は不要。
5. チューナーの公開番組表を読む場合は `READ_TV_LISTINGS` の実行時許可を付与する。パススルー入力の表示・選局だけならこの許可は要求しない。
6. ライブ画面でチャンネルまたは外部入力を選ぶ。

`READ_TV_LISTINGS`はAndroid TvProviderのdangerous権限である。
`ACCESS_ALL_EPG_DATA`・`ACCESS_TV_TUNER`・`TUNER_RESOURCE_ACCESS`などの
特権権限をKomorebiへ付与しない。

## 検査項目

### 静的

- [ ] TIS package/classハードコードが選局・一覧の条件に残っていない
- [ ] `COLUMN_INPUT_ID`と行IDを結合し、他TIS行への取り違えがない
- [ ] `TvInputInfo`パススルーとTvProviderのチャンネルを別に扱う
- [ ] 特定放送方式・ワンセグなど固有条件で一覧を不当に制限しない
- [ ] チャンネル・番組表・設定・切替・再生・終了の経路が汎用TIFへ接続される
- [ ] `getLiveStreamUrl`やExoPlayerへTIFのcontent URIを渡さない
- [ ] TIFの録画・予約APIがネットワークバックエンドへフォールバックしない

### 製品/実機（未実施）

- [ ] Androidビルド、APKインストール、実行時権限付与・拒否
- [ ] #116を含む複数TISを登録して、それぞれのチャンネル・番組表・選局を確認
- [ ] TvProvider row IDの再利用、TIS追加/削除、inputID変更時の取り違えがない
- [ ] `browsable=0`、`searchable=0`、未知broadcast type、番組情報なし、音声のみを確認
- [ ] HDMI等のpassthrough入力を選局し、映像の表示・資源解放を確認
- [ ] 選局、チャンネル切替、停止、バックグラウンド復帰、字幕、年齢制限、接続失敗を確認
- [ ] TIF/ネットワークバックエンド切替後、前バックエンドのEPGが混入しない
- [ ] TIFの録画・予約UIが誤成功しない、既存ネットワークバックエンドに退行がない

現時点でAndroid SDK/NDKビルド・端末接続・実機TIS/HDMI環境試験は未実施。
静的な差分検査はビルドや実機受入れ試験の代わりとしない。
