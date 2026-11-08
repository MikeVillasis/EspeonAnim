#pragma once

#include "Modules/ModuleInterface.h"

class FEspeonAnimEditorModule : public IModuleInterface
{
public:
	virtual void StartupModule() override;
	virtual void ShutdownModule() override;

private:
	void RegisterMenus();
	void OnImportMotionClicked();
};
