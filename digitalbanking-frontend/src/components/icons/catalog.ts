import home from "../../assets/icons/navigation/home.svg";
import accountNav from "../../assets/icons/navigation/account.svg";
import profileNav from "../../assets/icons/navigation/profile.svg";
import chevronRight from "../../assets/icons/navigation/chevron-right.svg";
import history from "../../assets/icons/navigation/history.svg";
import limits from "../../assets/icons/navigation/limits.svg";
import accountFeature from "../../assets/icons/feature/account.svg";
import profileFeature from "../../assets/icons/feature/profile.svg";
import emptyAccounts from "../../assets/icons/illustration/empty-accounts.svg";

export const navigationIcons = {
  home,
  account: accountNav,
  profile: profileNav,
  history,
  limits,
  chevronRight,
} as const;
export const featureIcons = {
  account: accountFeature,
  profile: profileFeature,
} as const;
export const illustrations = { emptyAccounts } as const;
