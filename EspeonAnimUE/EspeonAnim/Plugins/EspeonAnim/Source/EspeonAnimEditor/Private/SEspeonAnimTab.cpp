#include "SEspeonAnimTab.h"

#include "Animation/AnimSequence.h"
#include "DesktopPlatformModule.h"
#include "Editor.h"
#include "EspeonAnimSettings.h"
#include "EspeonProcessorSetup.h"
#include "EspeonQr.h"
#include "EspeonReceiver.h"
#include "Framework/Application/SlateApplication.h"
#include "HAL/PlatformApplicationMisc.h"
#include "HAL/PlatformProcess.h"
#include "IDesktopPlatform.h"
#include "Misc/MessageDialog.h"
#include "Misc/Paths.h"
#include "Styling/AppStyle.h"
#include "Subsystems/AssetEditorSubsystem.h"
#include "Widgets/Images/SImage.h"
#include "Widgets/Input/SButton.h"
#include "Widgets/Input/SComboBox.h"
#include "Widgets/Input/SEditableText.h"
#include "Widgets/Layout/SBorder.h"
#include "Widgets/Layout/SBox.h"
#include "Widgets/Layout/SScrollBox.h"
#include "Widgets/Notifications/SProgressBar.h"
#include "Widgets/Text/STextBlock.h"
#include "Widgets/Views/SListView.h"

#define LOCTEXT_NAMESPACE "EspeonAnimTab"

void SEspeonAnimTab::Construct(const FArguments&)
{
	QrBrush = MakeShared<FSlateBrush>();
	QrBrush->ImageSize = FVector2D(220, 220);
	QrBrush->DrawAs = ESlateBrushDrawType::Image;

	RefreshAddresses();

	ChildSlot
	[
		SNew(SBorder).Padding(10).BorderImage(FAppStyle::GetBrush("ToolPanel.GroupBorder"))
		[
			SNew(SVerticalBox)
			// ---- processor status + install
			+ SVerticalBox::Slot().AutoHeight().Padding(0, 0, 0, 10)
			[
				SNew(SVerticalBox)
				+ SVerticalBox::Slot().AutoHeight()
				[
					SNew(SHorizontalBox)
					+ SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center).Padding(0, 0, 8, 0)
					[
						SNew(STextBlock).Text(LOCTEXT("Proc", "Processor:")).Font(FAppStyle::GetFontStyle("BoldFont"))
					]
					+ SHorizontalBox::Slot().FillWidth(1.f).VAlign(VAlign_Center)
					[
						SNew(STextBlock).AutoWrapText(true)
						.Text_Lambda([]() { return FText::FromString(FEspeonProcessorSetup::Get().GetStatus().Text); })
						.ColorAndOpacity_Lambda([]() -> FSlateColor
						{
							switch (FEspeonProcessorSetup::Get().GetStatus().State)
							{
							case EEspeonProcessorState::Ready:        return FSlateColor(FLinearColor(0.4f, 0.9f, 0.5f));
							case EEspeonProcessorState::NotInstalled:
							case EEspeonProcessorState::Problems:
							case EEspeonProcessorState::InstallFailed: return FSlateColor(FLinearColor(1.f, 0.55f, 0.3f));
							default:                                   return FSlateColor::UseForeground();
							}
						})
					]
					+ SHorizontalBox::Slot().AutoWidth().Padding(8, 0, 0, 0)
					[
						SNew(SButton).Text(LOCTEXT("Install", "Install / Repair processor"))
						.ToolTipText(LOCTEXT("InstallTip", "Creates the Python environment and downloads the models (about 7.5 GB). Log: Saved/EspeonAnim/setup.log"))
						.IsEnabled_Lambda([]() { return !FEspeonProcessorSetup::Get().IsInstalling(); })
						.OnClicked_Lambda([this]() { InstallProcessor(); return FReply::Handled(); })
					]
				]
				+ SVerticalBox::Slot().AutoHeight().Padding(0, 4, 0, 0)
				[
					SNew(SVerticalBox)
					.Visibility_Lambda([]() { return FEspeonProcessorSetup::Get().IsInstalling() ? EVisibility::Visible : EVisibility::Collapsed; })
					+ SVerticalBox::Slot().AutoHeight()
					[
						SNew(SProgressBar).Percent_Lambda([]() -> TOptional<float> { return FEspeonProcessorSetup::Get().GetStatus().Progress; })
					]
					+ SVerticalBox::Slot().AutoHeight()
					[
						SNew(STextBlock).AutoWrapText(true).ColorAndOpacity(FSlateColor::UseSubduedForeground())
						.Text_Lambda([]() { return FText::FromString(FEspeonProcessorSetup::Get().GetStatus().StepMessage); })
					]
				]
			]			// ---- receiver + pairing
			+ SVerticalBox::Slot().AutoHeight()
			[
				SNew(SHorizontalBox)
				+ SHorizontalBox::Slot().AutoWidth().Padding(0, 0, 14, 0)
				[
					SNew(SBox).WidthOverride(228).HeightOverride(228)
					[
						SNew(SBorder).Padding(4).BorderImage(FAppStyle::GetBrush("WhiteBrush")).BorderBackgroundColor(FLinearColor::White)
						[
							SNew(SImage).Image_Lambda([this]() -> const FSlateBrush* { return QrBrush.Get(); })
						]
					]
				]
				+ SHorizontalBox::Slot().FillWidth(1.f)
				[
					SNew(SVerticalBox)
					+ SVerticalBox::Slot().AutoHeight().Padding(0, 0, 0, 6)
					[
						SNew(SHorizontalBox)
						+ SHorizontalBox::Slot().AutoWidth().Padding(0, 0, 8, 0)
						[
							SNew(SButton)
							.OnClicked_Lambda([this]() { ToggleReceiver(); return FReply::Handled(); })
							[
								SNew(STextBlock).Text_Lambda([]()
								{
									return FEspeonReceiver::Get().IsRunning()
										? LOCTEXT("StopRx", "Stop receiver") : LOCTEXT("StartRx", "Start receiver");
								})
							]
						]
						+ SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center)
						[
							SNew(STextBlock).Text_Lambda([this]() { return GetAddressText(); })
						]
					]
					+ SVerticalBox::Slot().AutoHeight().Padding(0, 0, 0, 6)
					[
						SNew(SHorizontalBox)
						+ SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center).Padding(0, 0, 8, 0)
						[
							SNew(STextBlock).Text(LOCTEXT("Lan", "PC address"))
						]
						+ SHorizontalBox::Slot().AutoWidth()
						[
							SAssignNew(AddressCombo, SComboBox<TSharedPtr<FString>>)
							.OptionsSource(&Addresses)
							.InitiallySelectedItem(SelectedAddress)
							.OnGenerateWidget_Lambda([](TSharedPtr<FString> Item) { return SNew(STextBlock).Text(FText::FromString(*Item)); })
							.OnSelectionChanged_Lambda([this](TSharedPtr<FString> Item, ESelectInfo::Type)
							{
								if (Item.IsValid()) { SelectedAddress = Item; RebuildQr(); }
							})
							[
								SNew(STextBlock).MinDesiredWidth(120).Text_Lambda([this]()
								{
									return FText::FromString(SelectedAddress.IsValid() ? *SelectedAddress : FString(TEXT("(no network)")));
								})
							]
						]
					]
					+ SVerticalBox::Slot().AutoHeight().Padding(0, 0, 0, 6)
					[
						SNew(SHorizontalBox)
						+ SHorizontalBox::Slot().AutoWidth().Padding(0, 0, 8, 0)
						[
							SNew(SButton).Text(LOCTEXT("NewCode", "New pairing code"))
							.ToolTipText(LOCTEXT("NewCodeTip", "Generates a new token. Phones paired with the old code stop working."))
							.OnClicked_Lambda([this]() { NewPairingCode(); return FReply::Handled(); })
						]
						+ SHorizontalBox::Slot().AutoWidth()
						[
							SNew(SButton).Text(LOCTEXT("ImportFile", "Import package/video file..."))
							.OnClicked_Lambda([this]() { ImportFile(); return FReply::Handled(); })
						]
					]
					+ SVerticalBox::Slot().AutoHeight().Padding(0, 0, 0, 4)
					[
						SNew(SEditableText).IsReadOnly(true).Text_Lambda([this]() { return FText::FromString(PairUrl); })
						.ToolTipText(LOCTEXT("UrlTip", "Pairing URL encoded in the QR code"))
					]
					+ SVerticalBox::Slot().AutoHeight()
					[
						SNew(STextBlock).AutoWrapText(true)
						.ColorAndOpacity(FSlateColor::UseSubduedForeground())
						.Text(LOCTEXT("Fw", "Phone and PC must be on the same Wi-Fi. Windows Firewall may ask to allow Unreal Editor on private networks the first time the receiver starts - allow it."))
					]
				]
			]
			// ---- jobs
			+ SVerticalBox::Slot().AutoHeight().Padding(0, 12, 0, 4)
			[
				SNew(STextBlock).Text(LOCTEXT("Jobs", "Jobs")).Font(FAppStyle::GetFontStyle("BoldFont"))
			]
			+ SVerticalBox::Slot().FillHeight(1.f)
			[
				SAssignNew(ListView, SListView<FRowPtr>)
				.ListItemsSource(&Rows)
				.SelectionMode(ESelectionMode::None)
				.OnGenerateRow(this, &SEspeonAnimTab::GenerateRow)
			]
		]
	];

	FEspeonProcessorSetup::Get().CheckAsync();
	RegisterActiveTimer(0.5f, FWidgetActiveTimerDelegate::CreateSP(this, &SEspeonAnimTab::TickRefresh));
	StartReceiver();
	RebuildQr();
}

SEspeonAnimTab::~SEspeonAnimTab()
{
	// The receiver keeps running after the tab closes so phones can still send; use the toggle to stop it.
}

FString SEspeonAnimTab::CurrentHost() const
{
	return SelectedAddress.IsValid() ? *SelectedAddress : FString(TEXT("127.0.0.1"));
}

FText SEspeonAnimTab::GetAddressText() const
{
	FEspeonReceiver& Rx = FEspeonReceiver::Get();
	if (!LastError.IsEmpty()) return FText::FromString(LastError);
	if (!Rx.IsRunning()) return LOCTEXT("Stopped", "Receiver stopped");
	return FText::FromString(FString::Printf(TEXT("Listening on %s:%d   PC: %s"), *CurrentHost(), Rx.GetPort(), *FEspeonReceiver::GetPcName()));
}

void SEspeonAnimTab::RefreshAddresses()
{
	Addresses.Reset();
	for (const FString& Ip : FEspeonReceiver::GetLanAddresses()) Addresses.Add(MakeShared<FString>(Ip));
	SelectedAddress = Addresses.Num() > 0 ? Addresses[0] : nullptr;
}

void SEspeonAnimTab::StartReceiver()
{
	LastError.Reset();
	FString Err;
	if (!FEspeonReceiver::Get().Start(UEspeonAnimSettings::Get()->ListenPort, Err)) LastError = Err;
}

void SEspeonAnimTab::ToggleReceiver()
{
	if (FEspeonReceiver::Get().IsRunning())
	{
		FEspeonReceiver::Get().Stop();
		LastError.Reset();
	}
	else
	{
		RefreshAddresses();
		if (AddressCombo.IsValid()) { AddressCombo->RefreshOptions(); AddressCombo->SetSelectedItem(SelectedAddress); }
		StartReceiver();
		RebuildQr();
	}
}

void SEspeonAnimTab::RebuildQr()
{
	PairUrl = FEspeonReceiver::BuildPairUrl(CurrentHost(), UEspeonAnimSettings::Get()->ListenPort, FEspeonReceiver::GetToken());
	FString Err;
	if (UTexture2D* Tex = FEspeonQr::CreateTexture(PairUrl, Err))
	{
		QrTexture.Reset(Tex);
		QrBrush->SetResourceObject(Tex);
	}
	else
	{
		LastError = Err;
	}
}

void SEspeonAnimTab::NewPairingCode()
{
	FEspeonReceiver::RegenerateToken();
	RebuildQr();
}

void SEspeonAnimTab::InstallProcessor()
{
	if (FEspeonProcessorSetup::Get().IsInstalling()) return;
	const FText Title = LOCTEXT("InstallTitle", "Install / Repair EspeonAnim processor");
	if (FMessageDialog::Open(EAppMsgType::YesNo, FEspeonProcessorSetup::GetLicenseNotice(), Title) != EAppReturnType::Yes) return;
	FString Err;
	if (!FEspeonProcessorSetup::Get().StartInstall(Err)) LastError = Err;
	else LastError.Reset();
}
void SEspeonAnimTab::ImportFile()
{
	IDesktopPlatform* DP = FDesktopPlatformModule::Get();
	if (!DP) return;
	TArray<FString> Files;
	const void* Parent = FSlateApplication::Get().FindBestParentWindowHandleForDialogs(nullptr);
	const FString Filter = TEXT("EspeonAnim input (*.espeon;*.mp4;*.mov;*.mkv;*.webm)|*.espeon;*.mp4;*.mov;*.mkv;*.webm|All files (*.*)|*.*");
	if (!DP->OpenFileDialog(Parent, TEXT("Import package or video"), FPaths::ProjectDir(), TEXT(""), Filter, EFileDialogFlags::None, Files) || Files.Num() == 0) return;
	FString Id, Err;
	if (FEspeonJobManager::Get().CreateJobFromFile(Files[0], Id, Err))
	{
		FEspeonJobManager::Get().RunAsync(Id);
	}
	else
	{
		LastError = Err;
	}
}

EActiveTimerReturnType SEspeonAnimTab::TickRefresh(double, float)
{
	// Rebuild the rows only when something visible changed, so buttons are not recreated under the cursor.
	FString Signature;
	TArray<FRowPtr> NewRows;
	for (const FEspeonJobStatus& S : FEspeonJobManager::Get().GetJobs())
	{
		Signature += FString::Printf(TEXT("%s|%s|%d|%.3f|%s|%s|%s|%d;"), *S.Id, *S.Name, int32(S.State), S.Progress, *S.Message, *S.Quality, *S.AnimAssetPath, S.Warnings.Num());
		NewRows.Add(MakeShared<FEspeonJobStatus>(S));
	}
	if (Signature != LastSignature)
	{
		LastSignature = Signature;
		Rows = MoveTemp(NewRows);
		if (ListView.IsValid()) ListView->RequestListRefresh();
	}
	return EActiveTimerReturnType::Continue;
}

TSharedRef<ITableRow> SEspeonAnimTab::GenerateRow(FRowPtr Item, const TSharedRef<STableViewBase>& Owner)
{
	const FEspeonJobStatus S = *Item;
	const bool bDone = S.State == EEspeonJobState::Done;
	const bool bFailed = S.State == EEspeonJobState::Failed;

	FString WarnText;
	for (const FEspeonJobWarning& W : S.Warnings)
	{
		WarnText += FString::Printf(TEXT("%s: %s%s\n"), *W.Code, *W.Message, W.Suggestion.IsEmpty() ? TEXT("") : *FString::Printf(TEXT(" (%s)"), *W.Suggestion));
	}
	WarnText.TrimEndInline();

	const FString Title = S.Name.IsEmpty() ? FString::Printf(TEXT("Job %s"), *S.Id) : S.Name;
	const FString StateText = S.Quality.IsEmpty()
		? FString(EspeonJobStateToString(S.State))
		: FString::Printf(TEXT("%s  -  quality: %s"), EspeonJobStateToString(S.State), *S.Quality);

	return SNew(STableRow<FRowPtr>, Owner)
	.Padding(FMargin(0, 3))
	[
		SNew(SBorder).Padding(8).BorderImage(FAppStyle::GetBrush("ToolPanel.DarkGroupBorder"))
		[
			SNew(SVerticalBox)
			+ SVerticalBox::Slot().AutoHeight()
			[
				SNew(SHorizontalBox)
				+ SHorizontalBox::Slot().FillWidth(1.f)
				[
					SNew(STextBlock).Text(FText::FromString(Title)).Font(FAppStyle::GetFontStyle("BoldFont"))
				]
				+ SHorizontalBox::Slot().AutoWidth()
				[
					SNew(STextBlock).Text(FText::FromString(StateText))
					.ColorAndOpacity(bFailed ? FSlateColor(FLinearColor(1.f, 0.35f, 0.3f)) : bDone ? FSlateColor(FLinearColor(0.4f, 0.9f, 0.5f)) : FSlateColor::UseForeground())
				]
			]
			+ SVerticalBox::Slot().AutoHeight().Padding(0, 4)
			[
				SNew(SProgressBar).Percent(S.Progress)
			]
			+ SVerticalBox::Slot().AutoHeight()
			[
				SNew(STextBlock).AutoWrapText(true).Text(FText::FromString(S.Message))
			]
			+ SVerticalBox::Slot().AutoHeight()
			[
				SNew(STextBlock).AutoWrapText(true).Visibility(WarnText.IsEmpty() ? EVisibility::Collapsed : EVisibility::Visible)
				.ColorAndOpacity(FSlateColor(FLinearColor(1.f, 0.8f, 0.3f))).Text(FText::FromString(WarnText))
			]
			+ SVerticalBox::Slot().AutoHeight().Padding(0, 4, 0, 0)
			[
				SNew(SHorizontalBox)
				+ SHorizontalBox::Slot().AutoWidth().Padding(0, 0, 6, 0)
				[
					SNew(SButton).Text(LOCTEXT("OpenAnim", "Open AnimSequence"))
					.IsEnabled(!S.AnimAssetPath.IsEmpty())
					.OnClicked_Lambda([S]()
					{
						const FString Obj = S.AnimAssetPath + TEXT(".") + FPackageName::GetShortName(S.AnimAssetPath);
						if (UAnimSequence* Seq = LoadObject<UAnimSequence>(nullptr, *Obj))
						{
							GEditor->GetEditorSubsystem<UAssetEditorSubsystem>()->OpenEditorForAsset(Seq);
						}
						return FReply::Handled();
					})
				]
				+ SHorizontalBox::Slot().AutoWidth().Padding(0, 0, 6, 0)
				[
					SNew(SButton).Text(LOCTEXT("OpenFbx", "Open FBX folder"))
					.OnClicked_Lambda([S]()
					{
						const FString Dir = S.FbxPath.IsEmpty() ? UEspeonAnimSettings::Get()->GetResolvedFbxDir() : FPaths::GetPath(S.FbxPath);
						IFileManager::Get().MakeDirectory(*Dir, true);
						FPlatformProcess::ExploreFolder(*Dir);
						return FReply::Handled();
					})
				]
				+ SHorizontalBox::Slot().AutoWidth()
				[
					SNew(SButton).Text(LOCTEXT("OpenJob", "Open job folder"))
					.OnClicked_Lambda([S]() { FPlatformProcess::ExploreFolder(*S.JobDir); return FReply::Handled(); })
				]
			]
		]
	];
}

#undef LOCTEXT_NAMESPACE
