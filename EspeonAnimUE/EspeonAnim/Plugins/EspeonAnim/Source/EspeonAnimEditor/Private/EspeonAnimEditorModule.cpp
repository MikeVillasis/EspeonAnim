#include "EspeonAnimEditorModule.h"
#include "EspeonMotionImporter.h"
#include "ToolMenus.h"
#include "DesktopPlatformModule.h"
#include "IDesktopPlatform.h"
#include "Framework/Application/SlateApplication.h"
#include "Animation/Skeleton.h"
#include "Misc/MessageDialog.h"
#include "Misc/Paths.h"
#include "ObjectTools.h"
#include "EspeonAnimSettings.h"
#include "EspeonJobManager.h"
#include "EspeonReceiver.h"
#include "SEspeonAnimTab.h"
#include "Framework/Docking/TabManager.h"
#include "Widgets/Docking/SDockTab.h"

#define LOCTEXT_NAMESPACE "EspeonAnimEditor"

IMPLEMENT_MODULE(FEspeonAnimEditorModule, EspeonAnimEditor)

const FName FEspeonAnimEditorModule::CaptureTabName(TEXT("EspeonAnimCapture"));

void FEspeonAnimEditorModule::StartupModule()
{
	FGlobalTabmanager::Get()->RegisterNomadTabSpawner(CaptureTabName, FOnSpawnTab::CreateRaw(this, &FEspeonAnimEditorModule::SpawnCaptureTab))
		.SetDisplayName(LOCTEXT("CaptureTab", "EspeonAnim Capture"))
		.SetMenuType(ETabSpawnerMenuType::Hidden);
	UToolMenus::RegisterStartupCallback(FSimpleMulticastDelegate::FDelegate::CreateRaw(this, &FEspeonAnimEditorModule::RegisterMenus));
}

void FEspeonAnimEditorModule::ShutdownModule()
{
	FEspeonReceiver::Get().Stop();
	FEspeonJobManager::Get().Shutdown();
	if (FSlateApplication::IsInitialized()) FGlobalTabmanager::Get()->UnregisterNomadTabSpawner(CaptureTabName);
	UToolMenus::UnRegisterStartupCallback(this);
	UToolMenus::UnregisterOwner(this);
}

void FEspeonAnimEditorModule::RegisterMenus()
{
	FToolMenuOwnerScoped OwnerScoped(this);
	UToolMenu* Tools = UToolMenus::Get()->ExtendMenu("LevelEditor.MainMenu.Tools");
	FToolMenuSection& Section = Tools->FindOrAddSection("EspeonAnimSection");
	Section.AddSubMenu("EspeonAnim", LOCTEXT("Espeon", "EspeonAnim"), LOCTEXT("EspeonTip", "EspeonAnim tools"),
		FNewToolMenuDelegate::CreateLambda([this](UToolMenu* Sub)
		{
			FToolMenuSection& S = Sub->AddSection("Main");
			S.AddMenuEntry("OpenCapture", LOCTEXT("OpenCapture", "EspeonAnim Capture"),
				LOCTEXT("OpenCaptureTip", "Pair a phone, receive captures over Wi-Fi and process them into animations"),
				FSlateIcon(),
				FUIAction(FExecuteAction::CreateLambda([]() { FGlobalTabmanager::Get()->TryInvokeTab(CaptureTabName); })));
			S.AddMenuEntry("ImportMotion", LOCTEXT("Import", "Import Motion JSON..."),
				LOCTEXT("ImportTip", "Import a motion.json onto the UE5 mannequin as an AnimSequence"),
				FSlateIcon(),
				FUIAction(FExecuteAction::CreateRaw(this, &FEspeonAnimEditorModule::OnImportMotionClicked)));
		}));
}

void FEspeonAnimEditorModule::OnImportMotionClicked()
{
	IDesktopPlatform* DP = FDesktopPlatformModule::Get();
	if (!DP) return;
	TArray<FString> Files;
	const void* Parent = FSlateApplication::Get().FindBestParentWindowHandleForDialogs(nullptr);
	if (!DP->OpenFileDialog(Parent, TEXT("Import Motion JSON"), FPaths::ProjectDir(), TEXT(""), TEXT("Motion JSON (*.json)|*.json"), EFileDialogFlags::None, Files) || Files.Num() == 0)
	{
		return;
	}
	USkeleton* Skel = UEspeonAnimSettings::Get()->TargetSkeleton.LoadSynchronous();
	if (!Skel)
	{
		FMessageDialog::Open(EAppMsgType::Ok, LOCTEXT("NoSkel", "EspeonAnim: SK_Mannequin not found."));
		return;
	}
	const FString Name = ObjectTools::SanitizeObjectName(FPaths::GetBaseFilename(Files[0]));
	FEspeonImportResult R = UEspeonMotionImporter::ImportMotion(Files[0], Skel, UEspeonAnimSettings::Get()->GetResolvedDestinationPath(), Name);
	FMessageDialog::Open(EAppMsgType::Ok, FText::FromString(R.bSuccess ? FString::Printf(TEXT("Imported %s"), *R.PackageName) : R.Error));
}

TSharedRef<SDockTab> FEspeonAnimEditorModule::SpawnCaptureTab(const FSpawnTabArgs&)
{
	return SNew(SDockTab).TabRole(ETabRole::NomadTab)
	[
		SNew(SEspeonAnimTab)
	];
}

#undef LOCTEXT_NAMESPACE
