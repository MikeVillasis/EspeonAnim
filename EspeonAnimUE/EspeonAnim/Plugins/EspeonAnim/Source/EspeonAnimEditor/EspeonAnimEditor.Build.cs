using UnrealBuildTool;

public class EspeonAnimEditor : ModuleRules
{
	public EspeonAnimEditor(ReadOnlyTargetRules Target) : base(Target)
	{
		PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;

		PublicDependencyModuleNames.AddRange(new string[] { "Core", "CoreUObject", "Engine" });

		PrivateDependencyModuleNames.AddRange(new string[]
		{
			"UnrealEd", "Json", "Slate", "SlateCore", "ToolMenus", "DesktopPlatform",
			"AssetRegistry", "InputCore"
		});
	}
}
