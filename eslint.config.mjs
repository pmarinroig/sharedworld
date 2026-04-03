import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    ignores: [
      "**/node_modules/**",
      "**/.wrangler/**",
      "**/build/**",
      "**/coverage/**",
      "**/dist/**",
      "**/run/**"
    ]
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["packages/{backend,shared}/{src,test}/**/*.ts"],
    languageOptions: {
      parserOptions: {
        project: [
          "./packages/shared/tsconfig.json",
          "./packages/shared/test/tsconfig.json",
          "./packages/backend/tsconfig.json",
          "./packages/backend/test/tsconfig.json"
        ],
        tsconfigRootDir: import.meta.dirname
      }
    },
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-unnecessary-type-assertion": "error",
      "@typescript-eslint/no-unsafe-assignment": "error",
      "@typescript-eslint/no-unsafe-call": "error",
      "@typescript-eslint/no-unsafe-member-access": "error",
      "@typescript-eslint/no-unsafe-return": "error",
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          "argsIgnorePattern": "^_",
          "caughtErrorsIgnorePattern": "^_"
        }
      ]
    }
  }
);
