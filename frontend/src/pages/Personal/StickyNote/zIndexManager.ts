let maxZIndex = 1;

export const getNextZIndex = () => {
  return ++maxZIndex;
};

export const getCurrentMaxZIndex = () => {
  return maxZIndex;
};
