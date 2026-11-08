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

#define LOCTEXT_NAMESPACE "EspeonAnimEditor"

IMPLEMENT_MODULE(FEspeonAnimEditorModule, EspeonAnimEditor)

void FEspeonAnimEditorModule::StartupModule()
{
	UToolMenus::RegisterStartupCallback(FSimpleMulticastDelegate::FDelegate::CreateRaw(this, &FEspeonAnimEditorModule::RegisterMenus));
}

void FEspeonAnimEditorModule::ShutdownModule()
{
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
	USkeleton* Skel = LoadObject<USkeleton>(nullptr, TEXT("/Game/Characters/Mannequins/Meshes/SK_Mannequin.SK_Mannequin"));
	if (!Skel)
	{
		FMessageDialog::Open(EAppMsgType::Ok, LOCTEXT("NoSkel", "EspeonAnim: SK_Mannequin not found."));
		return;
	}
	const FString Name = ObjectTools::SanitizeObjectName(FPaths::GetBaseFilename(Files[0]));
	FEspeonImportResult R = UEspeonMotionImporter::ImportMotion(Files[0], Skel, TEXT("/Game/EspeonAnim/Animations"), Name);
	FMessageDialog::Open(EAppMsgType::Ok, FText::FromString(R.bSuccess ? FString::Printf(TEXT("Imported %s"), *R.PackageName) : R.Error));
}

#undef LOCTEXT_NAMESPACE
