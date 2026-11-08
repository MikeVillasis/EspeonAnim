#pragma once

#include "Modules/ModuleInterface.h"
#include "Templates/SharedPointer.h"

class SDockTab;
class FSpawnTabArgs;

class FEspeonAnimEditorModule : public IModuleInterface
{
public:
	virtual void StartupModule() override;
	virtual void ShutdownModule() override;

	static const FName CaptureTabName;

private:
	void RegisterMenus();
	void OnImportMotionClicked();
	TSharedRef<SDockTab> SpawnCaptureTab(const FSpawnTabArgs& Args);
};
